package com.blokqr.app.crypto

import android.net.Uri
import kotlin.math.ln

/**
 * Détection côté client des « capability-URL » (liens personnels à usage unique).
 *
 * Avant de demander l'analyse PROFONDE (qui transmet l'URL complète à la
 * passerelle), l'application détecte les URL porteuses d'un jeton personnel
 * (réinitialisation, désinscription, lien magique, JWT...). Elle peut alors
 * demander le consentement explicite de l'utilisateur plutôt que d'exposer ce
 * secret. La même logique tourne côté serveur (défense en profondeur).
 */
object CapabilityUrl {

    private val SENSITIVE = Regex(
        "(token|reset|verify|confirm|magic|invite|unsubscribe|activation|" +
            "auth|session|sso|otp|signature|sig|key|secret|access)",
        RegexOption.IGNORE_CASE,
    )
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{6,}\\b")

    data class Assessment(val isCapability: Boolean, val reason: String = "")

    fun assess(url: String): Assessment {
        if (JWT.containsMatchIn(url)) return Assessment(true, "jeton JWT détecté")
        val uri = try { Uri.parse(url) } catch (e: Exception) { return Assessment(false) }

        for (name in uri.queryParameterNames) {
            if (SENSITIVE.containsMatchIn(name)) return Assessment(true, "paramètre « $name »")
            val v = uri.getQueryParameter(name) ?: ""
            if (looksRandom(v)) return Assessment(true, "valeur à forte entropie")
        }
        val path = uri.path ?: ""
        for (seg in path.split("/")) {
            if (looksRandom(seg)) return Assessment(true, "segment de chemin aléatoire")
        }
        if (SENSITIVE.containsMatchIn(path)) return Assessment(true, "chemin sensible")
        return Assessment(false)
    }

    private fun looksRandom(s: String): Boolean = s.length >= 20 && shannon(s) >= 3.5

    private fun shannon(s: String): Double {
        if (s.isEmpty()) return 0.0
        val counts = s.groupingBy { it }.eachCount()
        val n = s.length.toDouble()
        return counts.values.sumOf {
            val p = it / n
            -p * (ln(p) / ln(2.0))
        }
    }
}
