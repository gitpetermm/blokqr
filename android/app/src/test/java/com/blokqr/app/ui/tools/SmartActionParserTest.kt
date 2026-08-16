package com.blokqr.app.ui.tools
import com.blokqr.app.ui.tools.SmartActionParser.SmartKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * Tests unitaires (JVM) du cœur des actions intelligentes : détection du type,
 * reconnaissance des liens de réunion, extraction SMS/iCalendar. Pur, sans Android.
 */
class SmartActionParserTest {
    @Test fun detecte_les_schemas() {
        assertEquals(SmartKind.MAPS, SmartActionParser.kindOf("geo:48.85,2.35"))
        assertEquals(SmartKind.CALL, SmartActionParser.kindOf("tel:+33123456789"))
        assertEquals(SmartKind.SMS, SmartActionParser.kindOf("smsto:0612345678:Salut"))
        assertEquals(SmartKind.SMS, SmartActionParser.kindOf("sms:0612345678"))
        assertEquals(SmartKind.EMAIL, SmartActionParser.kindOf("mailto:a@b.com"))
        assertEquals(SmartKind.CONTACT, SmartActionParser.kindOf("BEGIN:VCARD\nVERSION:3.0\nFN:Jean\nEND:VCARD"))
        assertEquals(SmartKind.EVENT, SmartActionParser.kindOf("BEGIN:VEVENT\nSUMMARY:X\nEND:VEVENT"))
    }
    @Test fun ignore_les_liens_web_et_texte() {
        assertNull(SmartActionParser.kindOf("https://exemple.com"))
        assertNull(SmartActionParser.kindOf("http://zoom.us/j/1"))
        assertNull(SmartActionParser.kindOf("bonjour"))
    }
    @Test fun reconnait_les_reunions() {
        assertTrue(SmartActionParser.isMeetingLink("https://us02web.zoom.us/j/123"))
        assertTrue(SmartActionParser.isMeetingLink("https://zoom.us/j/1"))
        assertTrue(SmartActionParser.isMeetingLink("https://teams.microsoft.com/l/meetup/x"))
        assertTrue(SmartActionParser.isMeetingLink("https://meet.google.com/abc-defg-hij"))
        assertFalse(SmartActionParser.isMeetingLink("https://exemple.com/zoom.us"))
        assertFalse(SmartActionParser.isMeetingLink("geo:1,2"))
    }
    @Test fun extrait_sms() {
        assertEquals("0612345678" to "Salut", SmartActionParser.smsParts("smsto:0612345678:Salut"))
        assertEquals("0612345678" to null, SmartActionParser.smsParts("sms:0612345678"))
        assertEquals("0612" to "Coucou", SmartActionParser.smsParts("sms:0612?body=Coucou"))
    }
    @Test fun extrait_champs_ical() {
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:Jean Dupont\nTEL:+33123456789\nEMAIL:jean@ex.com\nEND:VCARD"
        assertEquals("Jean Dupont", SmartActionParser.icalField(vcard, "FN"))
        assertEquals("+33123456789", SmartActionParser.icalField(vcard, "TEL"))
        assertEquals("jean@ex.com", SmartActionParser.icalField(vcard, "EMAIL"))
        assertNull(SmartActionParser.icalField(vcard, "ORG"))
        // Déséchappement.
        assertEquals("A;B", SmartActionParser.icalField("BEGIN:VCARD\nFN:A\\;B\nEND:VCARD", "FN"))
    }
    @Test fun parse_date_debut() {
        assertNotNull(SmartActionParser.parseDtStartMillis("BEGIN:VEVENT\nDTSTART:20260710T143000\nEND:VEVENT"))
        assertNotNull(SmartActionParser.parseDtStartMillis("BEGIN:VEVENT\nDTSTART;VALUE=DATE:20261225\nEND:VEVENT"))
        assertNull(SmartActionParser.parseDtStartMillis("BEGIN:VEVENT\nSUMMARY:X\nEND:VEVENT"))
    }
}
