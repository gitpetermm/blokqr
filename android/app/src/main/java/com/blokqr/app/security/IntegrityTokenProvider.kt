package com.blokqr.app.security

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Fournit un token d'attestation Play Integrity (Â« Classic request Â») pour
 * autoriser le PROVISIONNEMENT initial d'une installation.
 *
 * RÃ”LE -------------------------------------------------------------------
 * Au premier POST /v1/install, l'app appelle [requestToken] avec un nonce
 * alÃ©atoire. Google Play Services renvoie un JWS signÃ© par Google qui inclut :
 *   - le nonce (anti-rejeu),
 *   - appIntegrity (PLAY_RECOGNIZED si l'app vient bien du Store),
 *   - deviceIntegrity (MEETS_DEVICE_INTEGRITY si appareil lÃ©gitime),
 *   - accountDetails (LICENSED si l'utilisateur possÃ¨de l'app sur le compte).
 *
 * Le serveur dÃ©code le JWS et vÃ©rifie tout cela ; il dÃ©rive ensuite un
 * device_hash STABLE (rÃ©sistant Ã  la dÃ©sinstall/rÃ©install) qu'il mappe Ã 
 * l'install_id. ConsÃ©quence : mÃªme si l'utilisateur rÃ©installe l'app, le
 * serveur reconnaÃ®t l'appareil et REJOUE le MÃŠME install_id (et donc le
 * MÃŠME quota Free quotidien).
 *
 * POURQUOI 1 SEUL APPEL PAR INSTALLATION ---------------------------------
 * L'API Play Integrity est rate-limitÃ©e (10 000 Â« classic Â» requÃªtes / jour
 * par projet en quota gratuit) et chaque appel coÃ»te 1â€“3 secondes. On NE
 * l'appelle PAS Ã  chaque scan : le HMAC par installation suffit pour
 * authentifier les requÃªtes suivantes, l'appareil ayant dÃ©jÃ  Ã©tÃ© attestÃ©
 * lors du provisionnement.
 *
 * GESTION DES Ã‰CHECS -----------------------------------------------------
 * Le service peut Ã©chouer pour des raisons lÃ©gitimes (pas de Play Services,
 * rÃ©seau, quota Google atteint, etc.). Chaque cas remonte une
 * [IntegrityRequestException] avec un [IntegrityFailureReason] explicite,
 * que l'appelant (InstallTokenManager) peut utiliser pour dÃ©cider d'un
 * fallback Ã©ventuel.
 */
class IntegrityTokenProvider(private val appContext: Context) {

    /** DonnÃ©es renvoyÃ©es au caller : nonce gÃ©nÃ©rÃ© + JWS Google. */
    data class IntegrityProof(
        /** Base64 URL-safe SANS padding, longueur compatible Play (16â€“500 octets). */
        val nonce: String,
        /** JWS opaque Ã©mis par Google Play Services, Ã  transmettre tel quel au backend. */
        val token: String,
    )

    /**
     * Demande un token d'intÃ©gritÃ© Ã  Google Play Services.
     *
     * @return [IntegrityProof] (nonce + token) Ã  envoyer au backend.
     * @throws IntegrityRequestException si Play Services ne peut pas rÃ©pondre.
     */
    fun requestToken(): IntegrityProof {
        val nonce = generateNonce()
        val token = blockingRequestToken(nonce)
        return IntegrityProof(nonce = nonce, token = token)
    }

    /**
     * GÃ©nÃ¨re un nonce alÃ©atoire (32 octets) encodÃ© en Base64 URL-safe sans
     * padding. Conforme aux contraintes de l'API (16 â‰¤ len â‰¤ ~500 octets,
     * Base64-URL safe). Le serveur rÃ©utilisera le mÃªme format pour comparer.
     */
    private fun generateNonce(): String {
        val raw = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(raw)
        return android.util.Base64.encodeToString(
            raw,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
    }

    /**
     * Appel synchrone bloquant Ã  Play Integrity. On reste sur un modÃ¨le
     * synchrone pour s'aligner sur InstallTokenManager (qui est dÃ©jÃ  bloquant
     * pour le provisionnement). Le timeout dur Ã©vite un blocage indÃ©fini si
     * Play Services ne rÃ©pond jamais.
     */
    private fun blockingRequestToken(nonce: String): String {
        val manager = IntegrityManagerFactory.create(appContext)
        val request = IntegrityTokenRequest.builder()
            .setNonce(nonce)
            .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
            .build()
        val task = manager.requestIntegrityToken(request)
        return try {
            val resp = Tasks.await(task, REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val token = resp.token()
            if (token.isNullOrBlank()) {
                throw IntegrityRequestException(IntegrityFailureReason.EMPTY_TOKEN)
            }
            token
        } catch (e: IntegrityServiceException) {
            Log.w(TAG, "Play Integrity a refusÃ© : code=${e.errorCode}", e)
            throw IntegrityRequestException(mapErrorCode(e.errorCode), e)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "Play Integrity : timeout local aprÃ¨s ${REQUEST_TIMEOUT_SECONDS}s")
            throw IntegrityRequestException(IntegrityFailureReason.TIMEOUT, e)
        } catch (e: Exception) {
            // ExecutionException ou autre : on remonte UNKNOWN pour ne rien masquer.
            Log.w(TAG, "Play Integrity a Ã©chouÃ© : ${e.message}", e)
            throw IntegrityRequestException(IntegrityFailureReason.UNKNOWN, e)
        }
    }

    /**
     * Mappe les codes d'erreur Play Integrity vers nos catÃ©gories internes.
     * On garde une granularitÃ© raisonnable pour permettre Ã  l'appelant de
     * diffÃ©rencier les cas attendus (Play absent) des cas anormaux.
     */
    private fun mapErrorCode(code: Int): IntegrityFailureReason = when (code) {
        IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
        IntegrityErrorCode.PLAY_STORE_NOT_FOUND,
        IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND,
        IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
        IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED -> IntegrityFailureReason.PLAY_SERVICES_MISSING

        IntegrityErrorCode.NETWORK_ERROR -> IntegrityFailureReason.NETWORK

        IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
        IntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID,
        IntegrityErrorCode.API_NOT_AVAILABLE -> IntegrityFailureReason.API_DISABLED

        IntegrityErrorCode.TOO_MANY_REQUESTS -> IntegrityFailureReason.RATE_LIMITED

        IntegrityErrorCode.APP_NOT_INSTALLED,
        IntegrityErrorCode.APP_UID_MISMATCH,
        IntegrityErrorCode.NONCE_IS_NOT_BASE64,
        IntegrityErrorCode.NONCE_TOO_LONG,
        IntegrityErrorCode.NONCE_TOO_SHORT -> IntegrityFailureReason.UNKNOWN

        IntegrityErrorCode.INTERNAL_ERROR -> IntegrityFailureReason.UNKNOWN
        else -> IntegrityFailureReason.UNKNOWN
    }

    companion object {
        private const val TAG = "IntegrityTokenProvider"

        /**
         * NumÃ©ro de projet Google Cloud (PAS l'ID alphanumÃ©rique) auquel
         * l'API Play Integrity est rattachÃ©e. Cette valeur est PUBLIQUE :
         * elle ne rÃ©vÃ¨le aucun secret. CÃ´tÃ© backend, le service account
         * de ce mÃªme projet vÃ©rifiera les tokens.
         */
        private const val CLOUD_PROJECT_NUMBER: Long = 489359198485L

        /** Taille du nonce (octets bruts). 32 = trÃ¨s largement suffisant. */
        private const val NONCE_BYTES = 32

        /**
         * Timeout dur cÃ´tÃ© client. Le SDK Play Integrity peut prendre 1â€“3 s
         * en conditions normales. 15 s donne de la marge pour les rÃ©seaux
         * lents, sans bloquer l'app indÃ©finiment.
         */
        private const val REQUEST_TIMEOUT_SECONDS: Long = 30
    }
}

/** CatÃ©gories d'Ã©chec normalisÃ©es remontÃ©es par [IntegrityTokenProvider]. */
enum class IntegrityFailureReason {
    /** Play Services / Play Store absents ou trop anciens. */
    PLAY_SERVICES_MISSING,
    /** Ã‰chec rÃ©seau pendant l'appel. */
    NETWORK,
    /** Timeout local (Tasks.await) dÃ©passÃ©. */
    TIMEOUT,
    /** Cloud project mal configurÃ©, API dÃ©sactivÃ©e, etc. */
    API_DISABLED,
    /** Trop de requÃªtes cÃ´tÃ© Google (quota). */
    RATE_LIMITED,
    /** Token vide alors que l'appel a rÃ©ussi (anormal). */
    EMPTY_TOKEN,
    /** Tout le reste (Ã  journaliser pour analyse). */
    UNKNOWN,
}

/** Exception remontÃ©e par [IntegrityTokenProvider] sur Ã©chec. */
class IntegrityRequestException(
    val reason: IntegrityFailureReason,
    cause: Throwable? = null,
) : Exception("integrity_request_failed:${reason.name.lowercase()}", cause)
