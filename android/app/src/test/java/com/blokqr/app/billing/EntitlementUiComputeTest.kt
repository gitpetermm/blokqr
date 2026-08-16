package com.blokqr.app.billing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests PURS du calcul du badge d'abonnement (EntitlementManager.computeUi).
 * Aucune dépendance Android : valide les paliers et l'arrondi des jours d'essai.
 */
class EntitlementUiComputeTest {

    private val day = 86_400L
    private val now = 1_700_000_000L

    @Test
    fun free_quand_non_pro() {
        val ui = EntitlementManager.computeUi(
            isPro = false, trialUntilEpoch = now + 10 * day, nowEpoch = now,
        )
        assertEquals(EntitlementTier.FREE, ui.tier)
        assertEquals(0, ui.trialDaysLeft)
    }

    @Test
    fun pro_quand_pro_sans_essai() {
        val ui = EntitlementManager.computeUi(isPro = true, trialUntilEpoch = 0L, nowEpoch = now)
        assertEquals(EntitlementTier.PRO, ui.tier)
        assertEquals(0, ui.trialDaysLeft)
    }

    @Test
    fun pro_quand_essai_expire() {
        val ui = EntitlementManager.computeUi(isPro = true, trialUntilEpoch = now - 1, nowEpoch = now)
        assertEquals(EntitlementTier.PRO, ui.tier)
        assertEquals(0, ui.trialDaysLeft)
    }

    @Test
    fun trial_arrondi_au_superieur() {
        // 3 jours + 1 s restants => 4 jours affichés.
        val ui = EntitlementManager.computeUi(isPro = true, trialUntilEpoch = now + 3 * day + 1, nowEpoch = now)
        assertEquals(EntitlementTier.TRIAL, ui.tier)
        assertEquals(4, ui.trialDaysLeft)
    }

    @Test
    fun trial_jours_exacts() {
        // Exactement 5 jours => 5.
        val ui = EntitlementManager.computeUi(isPro = true, trialUntilEpoch = now + 5 * day, nowEpoch = now)
        assertEquals(EntitlementTier.TRIAL, ui.tier)
        assertEquals(5, ui.trialDaysLeft)
    }

    @Test
    fun trial_minimum_un_jour() {
        // Quelques secondes restantes => au moins 1 jour affiché.
        val ui = EntitlementManager.computeUi(isPro = true, trialUntilEpoch = now + 30, nowEpoch = now)
        assertEquals(EntitlementTier.TRIAL, ui.tier)
        assertEquals(1, ui.trialDaysLeft)
    }
}