package com.blokqr.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.blokqr.app.Config
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Provisionne et stocke de façon sécurisée l'identifiant d'installation
 * (UUIDv4 stable, non-PII) et son secret HMAC associé (32 octets).
 *
 * Flux :
 *  1. Au premier démarrage de l'app : BlokQrApp appelle ensureProvisioned().
 *     Si pas encore d'install_id en local, on demande un token Play Integrity
 *     (anti-réinstall : un appareil déjà connu retombera sur le même quota
 *     Free côté serveur) puis on POST /v1/install (TLS only) et on stocke le
 *     couple (install_id, hmac_secret_b64) en EncryptedSharedPreferences
 *     (chiffrement AES-256 GCM via Android Keystore).
 *  2. Ensuite, à chaque requête HTTP vers l'API, HmacInterceptor lit ces
 *     valeurs SYNCHRONIQUEMENT pour signer la requête.
 *
 * Récupération automatique :
 *  - Si le backend répond 401 "install_unknown" (cas d'éviction LRU Redis),
 *    l'app détecte le code et appelle reprovision() pour repartir d'un état
 *    propre. Le quota du jour repart à zéro (c'est le comportement attendu :
 *    une nouvelle installation se voit allouer son propre quota).
 *
 * Mode Play Integrity (strict) :
 *  - On exige un token Integrity à CHAQUE provisionnement, y compris les
 *    reprovisions. Sans Play Services, l'app ne peut pas provisionner. Ce
 *    choix verrouille l'angle d'abus principal du quota Free (réinstaller
 *    pour réinitialiser). Les utilisateurs sans Play Services (rare, ~5 %)
 *    voient une erreur claire et peuvent utiliser le mode local dégradé
 *    (LocalAnalyzer).
 *
 * Sécurité :
 *  - EncryptedSharedPreferences chiffre les valeurs ET les clés avec une
 *    MasterKey AES-256-GCM dérivée du Keystore matériel (StrongBox si dispo).
 *  - Lecture synchrone : le coût d'ouverture du fichier est amorti par le
 *    cache statique de la bibliothèque (la 1re lecture après boot est plus
 *    lente, ensuite l'accès est en mémoire).
 */
class InstallTokenManager(private val appContext: Context) {

    /** Représentation interne d'une installation provisionnée. */
    data class Installation(
        val installId: String,
        /** Secret HMAC 32 octets, encodé base64 standard tel que renvoyé par le serveur. */
        val hmacSecretB64: String,
        /** Limite quota Free (informationnel ; le serveur reste l'arbitre réel). */
        val freeDailyQuota: Int,
        /** Limite quota Pro (informationnel). */
        val proDailyQuota: Int,
    )

    /**
     * Fournisseur de tokens Play Integrity. Construit en lazy pour éviter
     * d'initialiser le SDK si l'app a déjà un install_id en local (cas
     * largement majoritaire à partir du 2e démarrage).
     */
    private val integrityProvider: IntegrityTokenProvider by lazy {
        IntegrityTokenProvider(appContext)
    }

    // --- Stockage chiffré (lazy, créé à la première lecture/écriture) ---------
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // Client HTTP dédié au provisioning : volontairement minimal (pas d'épinglage
    // pour le premier provisionnement, qui est protégé par TLS seul -- modèle
    // Trust-On-First-Use). Une fois la 1re install effectuée, toutes les autres
    // requêtes passent par BlokQrApi (avec CertificatePinner).
    private val provisioningClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)   // marge pour cumul Integrity + POST
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Lecture SYNCHRONE de l'install_id stocké. Renvoie null si pas encore
     * provisionné (ou si on vient d'appeler clear()). Utilisé par HmacInterceptor
     * à chaque requête : doit être très rapide.
     */
    fun getInstallId(): String? = prefs.getString(KEY_INSTALL_ID, null)

    /**
     * Lecture SYNCHRONE du secret HMAC (octets bruts, déjà base64-décodés).
     * Renvoie null si pas encore provisionné.
     */
    fun getHmacSecret(): ByteArray? {
        val b64 = prefs.getString(KEY_HMAC_SECRET_B64, null) ?: return null
        return runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }
            .getOrNull()
    }

    /** Indique si l'app a déjà obtenu une installation auprès du serveur. */
    fun isProvisioned(): Boolean = getInstallId() != null

    /**
     * Garantit qu'une installation est disponible. À appeler depuis BlokQrApp
     * au démarrage (en arrière-plan, ne pas bloquer l'UI). Idempotent :
     * si déjà provisionné, retourne immédiatement.
     *
     * @throws InstallProvisioningException si le provisionnement échoue. L'app
     *         peut alors continuer en mode local dégradé (LocalAnalyzer) jusqu'à
     *         la prochaine tentative.
     */
    fun ensureProvisioned(): Installation {
        getInstallation()?.let { return it }
        return provisionFresh()
    }

    /**
     * Force un nouveau provisionnement (utile quand le serveur a répondu
     * 401 "install_unknown"). Efface l'état local et refait POST /v1/install.
     * Un NOUVEAU token Integrity est demandé : si l'appareil est déjà connu
     * du serveur (même device_hash), il retombera sur le même install_id.
     */
    fun reprovision(): Installation {
        clear()
        return provisionFresh()
    }

    /** Efface tout l'état local d'installation (déconnexion / debug). */
    fun clear() {
        prefs.edit()
            .remove(KEY_INSTALL_ID)
            .remove(KEY_HMAC_SECRET_B64)
            .remove(KEY_FREE_QUOTA)
            .remove(KEY_PRO_QUOTA)
            .apply()
    }

    /** Reconstruit l'objet Installation depuis le storage, ou null. */
    private fun getInstallation(): Installation? {
        val id = prefs.getString(KEY_INSTALL_ID, null) ?: return null
        val secret = prefs.getString(KEY_HMAC_SECRET_B64, null) ?: return null
        return Installation(
            installId = id,
            hmacSecretB64 = secret,
            freeDailyQuota = prefs.getInt(KEY_FREE_QUOTA, DEFAULT_FREE_QUOTA),
            proDailyQuota = prefs.getInt(KEY_PRO_QUOTA, DEFAULT_PRO_QUOTA),
        )
    }

    /**
     * Provisionnement effectif : demande d'abord un token Play Integrity puis
     * POST /v1/install en incluant le token (header + nonce dans le body). Si
     * tout va bien, persiste en EncryptedSharedPreferences et renvoie
     * l'Installation. Sur erreur, lève InstallProvisioningException.
     */
    private fun provisionFresh(): Installation {
        // Étape 1 : attestation Play Integrity (anti-réinstall).
        val proof = try {
            integrityProvider.requestToken()
        } catch (e: IntegrityRequestException) {
            Log.w(TAG, "Provisionnement refusé : ${e.reason}")
            // Mode strict : on bloque. L'app retombera sur LocalAnalyzer (mode
            // local non vérifié) tant que Play Integrity reste indisponible.
            throw InstallProvisioningException("integrity_${e.reason.name.lowercase()}", e)
        }

        // Étape 2 : POST /v1/install avec preuve d'intégrité.
        val payload = json.encodeToString(
            InstallRequestDto.serializer(),
            InstallRequestDto(nonce = proof.nonce),
        )
        val httpReq = Request.Builder()
            .url("${Config.API_BASE_URL}/v1/install")
            .header(HDR_INTEGRITY_TOKEN, proof.token)
            .post(payload.toRequestBody(jsonMedia))
            .build()

        val response = try {
            provisioningClient.newCall(httpReq).execute()
        } catch (e: Exception) {
            Log.w(TAG, "Provisionnement échoué : ${e.message}")
            throw InstallProvisioningException("network_error", e)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                throw InstallProvisioningException("http_${resp.code}")
            }
            val body = resp.body?.string()
                ?: throw InstallProvisioningException("empty_response")
            val dto = try {
                json.decodeFromString(InstallResponseDto.serializer(), body)
            } catch (e: Exception) {
                throw InstallProvisioningException("parse_error", e)
            }

            // Validations défensives : si le serveur renvoie n'importe quoi,
            // on refuse de stocker (sinon HMAC qui ne matchera jamais).
            if (dto.installId.isBlank() || dto.hmacSecretB64.isBlank()) {
                throw InstallProvisioningException("invalid_payload")
            }
            // Le secret doit être base64 valide ET 32 octets après décodage.
            val decoded = runCatching {
                android.util.Base64.decode(dto.hmacSecretB64, android.util.Base64.DEFAULT)
            }.getOrNull()
            if (decoded == null || decoded.size != HMAC_SECRET_BYTES_EXPECTED) {
                throw InstallProvisioningException("invalid_secret_size")
            }

            // Persistance atomique.
            prefs.edit()
                .putString(KEY_INSTALL_ID, dto.installId)
                .putString(KEY_HMAC_SECRET_B64, dto.hmacSecretB64)
                .putInt(KEY_FREE_QUOTA, dto.freeDailyQuota)
                .putInt(KEY_PRO_QUOTA, dto.proDailyQuota)
                .apply()

            Log.i(TAG, "Installation provisionnée : ${dto.installId.take(8)}…")
            return Installation(
                installId = dto.installId,
                hmacSecretB64 = dto.hmacSecretB64,
                freeDailyQuota = dto.freeDailyQuota,
                proDailyQuota = dto.proDailyQuota,
            )
        }
    }

    /** Body envoyé au backend : juste le nonce (le token est en header). */
    @Serializable
    private data class InstallRequestDto(
        val nonce: String,
    )

    /** DTO de la réponse de POST /v1/install (champ par champ du serveur). */
    @Serializable
    private data class InstallResponseDto(
        @kotlinx.serialization.SerialName("install_id")
        val installId: String,
        @kotlinx.serialization.SerialName("hmac_secret_b64")
        val hmacSecretB64: String,
        @kotlinx.serialization.SerialName("hmac_algorithm")
        val hmacAlgorithm: String = "HMAC-SHA256",
        @kotlinx.serialization.SerialName("canonical_format")
        val canonicalFormat: String = "",
        @kotlinx.serialization.SerialName("free_daily_quota")
        val freeDailyQuota: Int = DEFAULT_FREE_QUOTA,
        @kotlinx.serialization.SerialName("pro_daily_quota")
        val proDailyQuota: Int = DEFAULT_PRO_QUOTA,
    )

    companion object {
        private const val TAG = "InstallTokenManager"
        private const val FILE_NAME = "blokqr_install_secure"
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_HMAC_SECRET_B64 = "hmac_secret_b64"
        private const val KEY_FREE_QUOTA = "free_quota"
        private const val KEY_PRO_QUOTA = "pro_quota"
        private const val HMAC_SECRET_BYTES_EXPECTED = 32
        private const val DEFAULT_FREE_QUOTA = 7
        // Aligné sur la décision backend (pro_daily_quota = 500). Cette valeur
        // est purement informationnelle côté client : c'est le serveur qui
        // arbitre, mais la cohérence évite tout malentendu.
        private const val DEFAULT_PRO_QUOTA = 500

        /** Header HTTP transportant le JWS Google Play Integrity. */
        private const val HDR_INTEGRITY_TOKEN = "X-Integrity-Token"
    }
}

/**
 * Levée quand le provisionnement initial échoue. L'app peut alors continuer
 * en mode local dégradé (LocalAnalyzer) jusqu'à une prochaine tentative.
 */
class InstallProvisioningException(
    val reason: String,
    cause: Throwable? = null,
) : Exception("install_provisioning_failed:$reason", cause)