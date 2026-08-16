package com.blokqr.app.security

import android.util.Base64
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Intercepteur OkHttp injectant l'authentification HMAC par installation.
 *
 * Pour CHAQUE requête sortante (vers /v1/...) :
 *   1. Ajoute X-Install-Id (obligatoire) — toujours.
 *   2. Ajoute X-Timestamp (UNIX seconds), X-Nonce (16 octets aléatoires base64url)
 *      et X-Hmac (HMAC-SHA256 hex) calculés sur la chaîne canonique :
 *        METHOD\nPATH\nTS\nNONCE\nBODY_BYTES
 *      où BODY_BYTES sont les octets EFFECTIVEMENT envoyés (corps déjà chiffré
 *      si enveloppe PQ active — cohérent avec ce que le serveur reçoit).
 *
 * Récupération automatique sur 401 install_unknown :
 *   - Si le serveur a perdu l'install (éviction LRU Redis), il répond 401
 *     avec body contenant "install_unknown". On déclenche un reprovision()
 *     et on RETENTE la requête une seule fois avec le nouveau couple
 *     (install_id, secret). Si la 2e tentative échoue, on remonte l'erreur.
 *
 * Endpoints exemptés :
 *   - POST /v1/install lui-même (auto-référence impossible avant provisioning).
 *   - GET /manifest, GET /pq-pubkey, GET /health (endpoints système publics).
 *   Ces endpoints n'ont pas besoin d'identification.
 *
 * Note de design : on signe les octets ENVOYÉS sur le fil (pas le plaintext).
 * Le serveur fait pareil (il signe le body brut tel qu'arrivé, avant de
 * déchiffrer l'enveloppe). Cohérence garantie pour les requêtes enveloppées
 * comme pour les requêtes en clair.
 */
class HmacInterceptor(
    private val installTokenManager: InstallTokenManager,
) : Interceptor {

    private val secureRandom = SecureRandom()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Endpoints exemptés : on passe sans toucher.
        if (isExempt(original)) {
            return chain.proceed(original)
        }

        // Première tentative — avec l'install actuelle.
        val install = installTokenManager.getInstallation()
        if (install == null) {
            // Pas d'installation provisionnée : on envoie la requête en l'état
            // (le serveur en mode permissif log un warning ; en strict, il
            // refuse 401 ; côté app, ScanViewModel détecte et provisionne).
            return chain.proceed(original)
        }

        val signed = signRequest(original, install)
        val response = chain.proceed(signed)

        // Auto-récupération sur 401 install_unknown.
        if (response.code == 401 && response.bodyContains("install_unknown")) {
            response.close()
            Log.w(TAG, "Backend renvoie install_unknown — re-provisionnement")
            val refreshed = try {
                installTokenManager.reprovision()
            } catch (e: Exception) {
                Log.w(TAG, "Reprovisionnement échoué : ${e.message}")
                // On retransmet la 1re réponse-erreur en relançant la requête
                // d'origine (le ViewModel verra le 401 et basculera local).
                return chain.proceed(original)
            }
            return chain.proceed(signRequest(original, refreshed))
        }

        return response
    }

    /** Construit une requête signée à partir de l'originale et de l'installation. */
    private fun signRequest(
        original: Request,
        install: InstallTokenManager.Installation,
    ): Request {
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val nonce = generateNonce()

        // Lire les octets du body pour les inclure dans la signature, SANS
        // consommer le body original (OkHttp peut le re-envoyer en cas de retry
        // de connexion). On utilise un Buffer Okio.
        val bodyBytes: ByteArray = original.body?.let { body ->
            Buffer().also { body.writeTo(it) }.readByteArray()
        } ?: ByteArray(0)

        // Chaîne canonique EXACTE attendue par le serveur :
        //   METHOD\nPATH\nTS\nNONCE\nBODY
        // Le path est encodedPath() (sans query) car le serveur signe request.url.path.
        val path = original.url.encodedPath
        val headBytes = "${original.method.uppercase()}\n$path\n$ts\n$nonce\n"
            .toByteArray(Charsets.UTF_8)
        val canonical = headBytes + bodyBytes

        val secret = Base64.decode(install.hmacSecretB64, Base64.DEFAULT)
        val hmacHex = hmacSha256Hex(secret, canonical)

        return original.newBuilder()
            .header(HDR_INSTALL_ID, install.installId)
            .header(HDR_TIMESTAMP, ts)
            .header(HDR_NONCE, nonce)
            .header(HDR_HMAC, hmacHex)
            .build()
    }

    /** Génère un nonce base64url (16 octets), encodé sans padding. */
    private fun generateNonce(): String {
        val raw = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(raw)
        return Base64.encodeToString(
            raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }

    /** HMAC-SHA256 en hex minuscule (cohérent avec hmac_auth.py côté serveur). */
    private fun hmacSha256Hex(secret: ByteArray, data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val out = mac.doFinal(data)
        return out.joinToString("") { "%02x".format(it) }
    }

    /**
     * Endpoints non concernés par HMAC. Le critère est volontairement large
     * (path exact) pour ne pas casser des futurs endpoints système.
     */
    private fun isExempt(request: Request): Boolean {
        val path = request.url.encodedPath
        return path == "/v1/install"
            || path == "/manifest"
            || path == "/pq-pubkey"
            || path == "/health"
            || path == "/pubkey"
    }

    /**
     * Lit le body d'une réponse SANS la consommer (peekBody), pour pouvoir
     * détecter le motif install_unknown tout en laissant la réponse réutilisable.
     */
    private fun Response.bodyContains(needle: String): Boolean {
        val peek = peekBody(MAX_PEEK_BYTES)
        val text = try { peek.string() } catch (e: Exception) { "" }
        return text.contains(needle, ignoreCase = true)
    }

    companion object {
        private const val TAG = "HmacInterceptor"
        private const val HDR_INSTALL_ID = "X-Install-Id"
        private const val HDR_TIMESTAMP = "X-Timestamp"
        private const val HDR_NONCE = "X-Nonce"
        private const val HDR_HMAC = "X-Hmac"
        private const val NONCE_BYTES = 16
        private const val MAX_PEEK_BYTES = 2048L
    }

    /** Helper de visibilité internal pour le manager (lecture combinée). */
    private fun InstallTokenManager.getInstallation(): InstallTokenManager.Installation? {
        val id = getInstallId() ?: return null
        val secret = getHmacSecret() ?: return null
        // Re-encode pour reproduire la structure attendue par signRequest()
        // (qui base64-decode à nouveau). C'est volontaire : signRequest reste
        // indépendant de la représentation interne du manager.
        val b64 = Base64.encodeToString(secret, Base64.NO_WRAP)
        return InstallTokenManager.Installation(
            installId = id,
            hmacSecretB64 = b64,
            freeDailyQuota = 0,  // non utilisé par signRequest
            proDailyQuota = 0,
        )
    }
}