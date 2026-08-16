package com.blokqr.app.net

import com.blokqr.app.model.LocalReason
import com.blokqr.app.security.InstallProvisioningException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Classe une exception d'analyse en une [LocalReason] récupérable, ou `null`
 * si l'erreur est inattendue (vrai bug à afficher).
 *
 * Objectif Phase 3 : ne JAMAIS afficher une erreur réseau brute du type
 * « Unable to resolve host » ou « HTTP 401 » à l'utilisateur. Les cas
 * transitoires (hors-ligne, provisionnement, serveur 5xx) basculent
 * silencieusement sur l'analyse locale avec un bandeau contextuel.
 *
 * Hiérarchie de décision :
 *   1. Erreur réseau bas niveau (IOException typée)        -> OFFLINE
 *   2. InstallProvisioningException (selon sa raison)      -> OFFLINE / SERVER / INITIALIZING
 *   3. AnalysisException (HTTP wrappé par BlokQrApi)        -> selon le code
 *   4. IOException générique                                -> OFFLINE
 *   5. Tout le reste                                        -> null (vraie erreur)
 */
object ExceptionClassifier {

    fun classify(e: Throwable): LocalReason? {
        // 1. Réseau bas niveau (inclut les causes chaînées).
        if (isNetworkError(e)) return LocalReason.OFFLINE

        // 2. Provisionnement d'installation échoué.
        if (e is InstallProvisioningException) {
            val reason = e.reason
            return when {
                reason.startsWith("network") -> LocalReason.OFFLINE
                reason.startsWith("http_5")  -> LocalReason.SERVER_UNAVAILABLE
                else                         -> LocalReason.INITIALIZING
            }
        }

        // 3. Erreurs applicatives renvoyées par BlokQrApi (HTTP wrappé).
        if (e is AnalysisException) {
            val msg = e.message.orEmpty()
            return when {
                msg.startsWith("quota_exceeded")               -> LocalReason.QUOTA_EXHAUSTED
                msg.startsWith("pro_required")                 -> null            // géré dans le flux deepen
                hasHttpCode(msg, 401) || hasHttpCode(msg, 403) -> LocalReason.INITIALIZING
                hasServerErrorCode(msg)                        -> LocalReason.SERVER_UNAVAILABLE
                // « Service indisponible. » sans code HTTP = connexion impossible.
                msg.startsWith("Service indisponible") && !msg.contains("HTTP") -> LocalReason.OFFLINE
                else                                           -> null
            }
        }

        // 4. IOException générique non typée.
        if (e is IOException) return LocalReason.OFFLINE

        // 5. Inconnu : vraie erreur, à afficher.
        return null
    }

    /** Parcourt la chaîne de causes à la recherche d'une panne réseau typée. */
    private fun isNetworkError(e: Throwable): Boolean {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 8) {
            when (cur) {
                is UnknownHostException,
                is SocketTimeoutException,
                is ConnectException,
                is SSLException -> return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    /** Vrai si le message contient exactement « HTTP <code> ». */
    private fun hasHttpCode(msg: String, code: Int): Boolean = msg.contains("HTTP $code")

    /** Vrai si le message contient un code serveur 5xx (HTTP 500–599). */
    private fun hasServerErrorCode(msg: String): Boolean =
        Regex("""HTTP\s+5\d\d""").containsMatchIn(msg)
}