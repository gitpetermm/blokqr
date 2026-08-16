package com.blokqr.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlTextExtractorTest {

    @Test fun extraitUneUrlAvecSchema() {
        assertEquals(
            "https://exemple.com/page?x=1",
            UrlTextExtractor.extract("Visitez https://exemple.com/page?x=1 vite"),
        )
    }

    @Test fun prefixeUnDomaineNu() {
        assertEquals(
            "https://exemple.fr",
            UrlTextExtractor.extract("Promo sur exemple.fr aujourd'hui"),
        )
    }

    @Test fun retireLaPonctuationFinale() {
        assertEquals(
            "http://test.org",
            UrlTextExtractor.extract("Lien: http://test.org."),
        )
    }

    @Test fun privilegieLeSchemaExplicite() {
        assertEquals(
            "https://reel.io/x",
            UrlTextExtractor.extract("voir exemple.com ou https://reel.io/x"),
        )
    }

    @Test fun conserveSousDomaineEtPort() {
        assertEquals(
            "http://a.b.example.co.uk:8080/p",
            UrlTextExtractor.extract("Adresse http://a.b.example.co.uk:8080/p ok"),
        )
    }

    @Test fun renvoieNullSansUrl() {
        assertNull(UrlTextExtractor.extract("aucune adresse ici, juste du texte"))
        assertNull(UrlTextExtractor.extract(""))
    }
}