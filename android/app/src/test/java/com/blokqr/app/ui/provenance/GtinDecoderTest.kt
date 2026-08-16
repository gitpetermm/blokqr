package com.blokqr.app.ui.provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * Tests unitaires (JVM) du décodeur GTIN et de la table GS1. 100 % hors-ligne,
 * sans dépendance Android : fige la clé de contrôle (modulo 10), l'expansion
 * UPC-E, la normalisation et le lookup des préfixes GS1.
 */
class GtinDecoderTest {
    // --- EAN-13 ----------------------------------------------------------
    @Test fun ean13_valide_prefixe_france() {
        val info = GtinDecoder.decode("3017620422003", "ean13")
        assertNotNull(info); info!!
        assertEquals("3017620422003", info.gtin)
        assertEquals("GTIN-13", info.format)
        assertTrue(info.validCheck)
        assertEquals(301, info.prefix)
        assertEquals(Gs1Kind.MEMBER_ORG, info.issuer?.kind)
        assertEquals("FR", info.issuer?.iso)
    }
    @Test fun ean13_cle_invalide() {
        // Même numéro, dernier chiffre faux.
        val info = GtinDecoder.decode("3017620422004", "ean13")
        assertNotNull(info); info!!
        assertFalse(info.validCheck)
    }
    // --- UPC-A -----------------------------------------------------------
    @Test fun upca_normalise_en_gtin13_us() {
        val info = GtinDecoder.decode("036000291452", "upc_a")
        assertNotNull(info); info!!
        assertEquals("0036000291452", info.gtin)          // 0 en tête
        assertEquals("GTIN-12 (UPC-A)", info.format)
        assertTrue(info.validCheck)
        assertEquals(3, info.prefix)                       // 003 -> 0..19
        assertEquals("US", info.issuer?.iso)
    }
    // --- EAN-8 -----------------------------------------------------------
    @Test fun ean8_pas_de_pays() {
        val info = GtinDecoder.decode("96385074", "ean8")
        assertNotNull(info); info!!
        assertEquals("GTIN-8 (EAN-8)", info.format)
        assertTrue(info.validCheck)
        assertNull(info.prefix)                            // pas de préfixe pays
        assertNull(info.issuer)
    }
    // --- UPC-E -----------------------------------------------------------
    @Test fun upce_etendu_en_gtin13() {
        val info = GtinDecoder.decode("01278906", "upc_e")
        assertNotNull(info); info!!
        assertEquals("UPC-E", info.format)
        assertEquals(13, info.gtin.length)
        assertTrue(info.validCheck)                        // clé recalculée à l'expansion
    }
    // --- Entrées non exploitables ---------------------------------------
    @Test fun longueur_invalide_renvoie_null() {
        assertNull(GtinDecoder.decode("12345", "code128"))
        assertNull(GtinDecoder.decode("", "ean13"))
    }
    // --- Reconnaissance de symbologie -----------------------------------
    @Test fun symbologie_produit() {
        assertTrue(GtinDecoder.isProductSymbology("ean13"))
        assertTrue(GtinDecoder.isProductSymbology("EAN_13"))   // insensible à la casse
        assertTrue(GtinDecoder.isProductSymbology("upc_a"))
        assertFalse(GtinDecoder.isProductSymbology("qr"))
        assertFalse(GtinDecoder.isProductSymbology("code128"))
    }
    // --- Table des préfixes GS1 -----------------------------------------
    @Test fun prefixes_gs1() {
        assertEquals(Gs1Kind.MEMBER_ORG, Gs1Prefixes.lookup(690).kind)   // Chine
        assertEquals("CN", Gs1Prefixes.lookup(690).iso)
        assertEquals(Gs1Kind.RESTRICTED, Gs1Prefixes.lookup(20).kind)    // usage interne
        assertEquals(Gs1Kind.ISBN, Gs1Prefixes.lookup(978).kind)         // livre
        assertEquals(Gs1Kind.ISSN, Gs1Prefixes.lookup(977).kind)         // publication série
        assertEquals(Gs1Kind.GS1_GLOBAL, Gs1Prefixes.lookup(950).kind)   // GS1 Global Office
        assertEquals(Gs1Kind.MEMBER_ORG, Gs1Prefixes.lookup(500).kind)   // Royaume-Uni
        assertEquals("GB", Gs1Prefixes.lookup(500).iso)
    }
}
