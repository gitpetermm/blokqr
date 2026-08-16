package com.blokqr.app.ui.tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * Tests unitaires (JVM) des constructeurs de payloads du générateur. Logique
 * pure, sans Android : fige l'exactitude vCard / Wi-Fi / geo / vEvent et de
 * l'échappement — garantit le round-trip génération -> re-scan.
 */
class CodePayloadsTest {
    // --- vCard -----------------------------------------------------------
    @Test fun vcard_minimal() {
        val v = CodePayloads.vCard("Jean", "", "", "", null, "", "", "", "", "", "", "")
        assertTrue(v != null); v!!
        assertTrue(v.startsWith("BEGIN:VCARD"))
        assertTrue(v.contains("VERSION:3.0"))
        assertTrue(v.contains("N:;Jean;;;"))
        assertTrue(v.contains("FN:Jean"))
        assertTrue(v.endsWith("END:VCARD"))
    }
    @Test fun vcard_echappement() {
        val v = CodePayloads.vCard("A;B", "", "", "", null, "", "", "", "", "", "", "")
        assertTrue(v != null); v!!
        // Le point-virgule du prénom est échappé en \; (pas un séparateur vCard).
        assertTrue(v.contains("A\\;B"))
    }
    @Test fun vcard_null_si_vide() {
        assertNull(CodePayloads.vCard("", "", "", "", null, "", "", "", "", "", "", ""))
    }
    // --- Wi-Fi -----------------------------------------------------------
    @Test fun wifi_wpa() {
        assertEquals(
            "WIFI:S:Net;T:WPA;P:pass123;;",
            CodePayloads.wifi("Net", "WPA", "pass123", false),
        )
    }
    @Test fun wifi_ouvert_ignore_mdp() {
        assertEquals(
            "WIFI:S:Net;T:nopass;;",
            CodePayloads.wifi("Net", "nopass", "secret", false),
        )
    }
    @Test fun wifi_masque() {
        assertEquals(
            "WIFI:S:Net;T:WPA;P:p;H:true;;",
            CodePayloads.wifi("Net", "WPA", "p", true),
        )
    }
    @Test fun wifi_echappement() {
        val w = CodePayloads.wifi("A;B", "WPA", "c:d", false)
        assertTrue(w != null); w!!
        assertTrue(w.contains("A\\;B"))   // ; échappé
        assertTrue(w.contains("c\\:d"))   // : échappé
    }
    @Test fun wifi_null_si_ssid_vide() {
        assertNull(CodePayloads.wifi("", "WPA", "p", false))
    }
    // --- geo -------------------------------------------------------------
    @Test fun geo_point_et_virgule() {
        assertEquals("geo:48.8566,2.3522", CodePayloads.geo("48.8566", "2.3522"))
        assertEquals("geo:48.8566,2.3522", CodePayloads.geo("48,8566", "2,3522"))
    }
    @Test fun geo_null_si_invalide() {
        assertNull(CodePayloads.geo("abc", "2.0"))
    }
    // --- vEvent ----------------------------------------------------------
    @Test fun vevent_horaire() {
        val e = CodePayloads.vEvent("Meeting", false, "2026-07-10", "14:30", "", "", "", "")
        assertTrue(e != null); e!!
        assertTrue(e.contains("SUMMARY:Meeting"))
        assertTrue(e.contains("DTSTART:20260710T143000"))
        assertTrue(e.endsWith("END:VEVENT"))
    }
    @Test fun vevent_journee_entiere() {
        val e = CodePayloads.vEvent("Fete", true, "2026-12-25", "", "", "", "", "")
        assertTrue(e != null); e!!
        assertTrue(e.contains("DTSTART;VALUE=DATE:20261225"))
    }
    @Test fun vevent_null_si_incomplet() {
        assertNull(CodePayloads.vEvent("", false, "2026-07-10", "", "", "", "", ""))
        assertNull(CodePayloads.vEvent("X", false, "", "", "", "", "", ""))
    }
    // --- formatDateTime --------------------------------------------------
    @Test fun format_datetime() {
        assertEquals("20260710T143000", CodePayloads.formatDateTime("2026-07-10", "14:30", false))
        assertEquals("20261225", CodePayloads.formatDateTime("2026-12-25", "", true))
        assertEquals("20260710T000000", CodePayloads.formatDateTime("2026-07-10", "", false))
        assertNull(CodePayloads.formatDateTime("2026", "", false))
    }
}
