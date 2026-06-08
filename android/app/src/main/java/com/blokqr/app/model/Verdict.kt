package com.blokqr.app.model

import androidx.compose.ui.graphics.Color
import com.blokqr.app.ui.theme.DangerAmber
import com.blokqr.app.ui.theme.MaliciousRed
import com.blokqr.app.ui.theme.NeutralGrey
import com.blokqr.app.ui.theme.SafeGreen

/**
 * Les trois paliers de verdict + le cas neutre (contenu non navigable).
 * Couleurs, messages et émotions sont alignés sur le cahier des charges.
 */
enum class Verdict(
    val wireValue: String,
    val color: Color,
    val label: String,
    val emoji: String,
) {
    SAFE("safe", SafeGreen, "Sécurisé", "\u2714\uFE0F"),
    DANGEROUS("dangerous", DangerAmber, "Prudence", "\u26A0\uFE0F"),
    MALICIOUS("malicious", MaliciousRed, "Dangereux", "\uD83D\uDED1"),
    UNKNOWN("unknown", DangerAmber, "Non vérifié", "\u2753"),
    NEUTRAL("neutral", NeutralGrey, "Information", "\u2139\uFE0F");

    /** Comportement d'ouverture autorisé selon le palier. */
    val opening: OpeningPolicy
        get() = when (this) {
            SAFE -> OpeningPolicy.ALLOWED_WITH_LIGHT_WARNING
            DANGEROUS -> OpeningPolicy.SANDBOX_ONLY
            MALICIOUS -> OpeningPolicy.BLOCKED
            // Fail-closed : analyse incomplète => pas d'ouverture directe,
            // inspection en bac à sable uniquement, ou relancer l'analyse.
            UNKNOWN -> OpeningPolicy.SANDBOX_ONLY
            NEUTRAL -> OpeningPolicy.ALLOWED_WITH_LIGHT_WARNING
        }

    companion object {
        fun fromWire(value: String?): Verdict =
            entries.firstOrNull { it.wireValue == value } ?: NEUTRAL
    }
}

enum class OpeningPolicy {
    /** Ouverture directe possible (avertissement léger), ou bac à sable. */
    ALLOWED_WITH_LIGHT_WARNING,
    /** Ouverture bloquée ; forçage uniquement dans le navigateur isolé. */
    SANDBOX_ONLY,
    /** Aucune ouverture possible. */
    BLOCKED,
}
