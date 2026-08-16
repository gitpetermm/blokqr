package com.blokqr.app.analyzer

/**
 * Données statiques enrichies pour LocalAnalyzer V2 — mode dégradé.
 *
 * Toutes les structures sont conçues pour un lookup O(1) (Set/Map immutables).
 * Aucune logique métier ici : juste des données de référence séparées du
 * fichier LocalAnalyzer.kt pour faciliter la maintenance.
 *
 * Sources des listes (toutes vérifiées commercial-compatible) :
 *  - Confusables : tables Unicode Consortium "Recommended Confusables" (UTS #39)
 *  - Brands typosquattées : top 10 mondial + top 10 banques FR
 *  - URL shorteners : liste publique des plus utilisés pour phishing
 *  - TLDs suspects : statistiques URLhaus/PhishTank 2024-2026
 *
 * ATTENTION : ne JAMAIS ajouter de domaine légitime ici (ex: paypal.com,
 * google.com). Ces sets servent à DÉTECTER des imitations, pas à bloquer
 * les originaux.
 */
internal object LocalThreatData {

    // ----------------------------------------------------------------------- //
    //  Schemes dangereux
    // ----------------------------------------------------------------------- //
    /**
     * Schemes d'URL qui n'ont AUCUNE raison légitime dans un QR code grand public.
     * `intent://` peut lancer des activités Android arbitraires (deep links).
     * `javascript:` exécute du code dans le navigateur de l'utilisateur.
     * `data:text/html` peut contenir une page HTML phishing complète embarquée.
     * `file:` accède au système de fichiers local.
     * `vbscript:` exécute du VBScript (Windows surtout).
     */
    val DANGEROUS_SCHEMES: List<String> = listOf(
        "intent://",
        "javascript:",
        "data:text/html",
        "data:application/",
        "file://",
        "vbscript:",
        "jar:",
        "view-source:",
    )

    // ----------------------------------------------------------------------- //
    //  TLDs à haut risque (élargissement de la liste existante)
    // ----------------------------------------------------------------------- //
    /**
     * TLDs statistiquement abusés pour phishing/malware (analyse 2024-2026).
     * Note : présence ici = légère pénalité (+0,3 au score), pas un blocage.
     * Beaucoup de sites légitimes existent sur ces TLDs (ex: linktr.ee).
     */
    val HIGH_RISK_TLDS_EXTENDED: Set<String> = setOf(
        // Freenom historique (abuseurs n°1 mondiaux)
        "tk", "ml", "ga", "cf", "gq",
        // Nouveaux TLDs à très forte prévalence d'abus
        "top", "xyz", "icu", "cyou", "click", "support", "kim",
        "loan", "men", "download", "stream", "work", "country", "link",
        "buzz", "live", "online", "site", "website", "space",
        // Confusion fichiers (récents, dangereux)
        "zip", "mov",
        // Free hosting communément utilisé pour phishing
        "pages.dev", "vercel.app", "netlify.app", "firebaseapp.com",
        "repl.co", "glitch.me",
    )

    // ----------------------------------------------------------------------- //
    //  Raccourcisseurs d'URL (étendu)
    // ----------------------------------------------------------------------- //
    /**
     * Services de raccourcissement d'URL. Hors-ligne, on ne peut PAS suivre
     * la redirection : la destination réelle est INCONNUE. C'est un signal
     * de prudence (poids modéré), pas une menace certaine.
     */
    val URL_SHORTENERS_EXTENDED: Set<String> = setOf(
        // Top mondial
        "bit.ly", "tinyurl.com", "goo.gl", "t.co", "ow.ly", "is.gd",
        "buff.ly", "adf.ly", "bit.do", "cutt.ly", "rebrand.ly",
        "shorturl.at", "rb.gy", "tiny.cc", "shorte.st", "soo.gd",
        "qr.ae", "v.gd", "tr.im", "lnkd.in", "trib.al",
        // Moins connus mais utilisés en phishing
        "shorturl.com", "smarturl.it", "u.to", "x.co", "yourls.org",
        "bl.ink", "po.st", "lnk.bz", "fb.me", "ift.tt",
    )

    // ----------------------------------------------------------------------- //
    //  Brands typosquattées (top mondial + banques FR)
    // ----------------------------------------------------------------------- //
    /**
     * Marques cibles fréquentes de typosquatting. Chaque entrée est le nom
     * SANS le TLD : la détection considère que `paypa1` ou `paypa-l` matchent
     * `paypal` (distance Levenshtein 1 ou caractères confusables).
     *
     * Les marques sont stockées en minuscules, sans `www`, sans TLD.
     */
    val TYPOSQUAT_BRANDS: Set<String> = setOf(
        // Top tech mondial
        "google", "youtube", "gmail",
        "microsoft", "outlook", "office365", "onedrive",
        "apple", "icloud",
        "facebook", "instagram", "whatsapp", "messenger",
        "amazon",
        "netflix",
        "paypal",
        "twitter",
        "linkedin",
        "telegram", "discord",
        "github", "gitlab",
        // Services publics France
        "ameli",
        "impots", "service-public", "caf",
        "laposte", "colissimo",
        // Banques majeures France
        "bnpparibas", "credit-agricole", "creditmutuel",
        "societegenerale", "labanquepostale", "caisse-epargne",
        "lcl", "boursorama", "fortuneo", "hellobank",
        "n26", "revolut",
        // E-commerce / livraison
        "dhl", "fedex", "ups", "chronopost", "ups",
    )

    // ----------------------------------------------------------------------- //
    //  Confusables Unicode (Cyrillique / Grec → Latin)
    // ----------------------------------------------------------------------- //
    /**
     * Mapping de caractères Unicode visuellement identiques à des lettres
     * latines mais provenant d'autres scripts (Cyrillique, Grec, etc.).
     *
     * Utilisé pour NORMALISER un domaine suspect AVANT comparaison avec la
     * blocklist : `gооgle.com` (avec `о` cyrillique U+043E) devient
     * `google.com` (avec `o` latin) → match blocklist.
     *
     * ⚠️ NE CONTIENT PAS les substitutions chiffres → lettres (`0→o`, `1→l`).
     * Ces substitutions sont AGRESSIVES et créeraient des faux positifs
     * (ex: `paypa1.com` deviendrait `paypal.com` exact, et serait
     * considéré comme légitime au lieu de typosquat). Ces substitutions
     * sont appliquées séparément dans `normalizeForBrandComparison()`.
     *
     * Source : Unicode UTS #39 "Recommended Confusables" (extrait pour les
     * caractères les plus dangereux). Liste non exhaustive volontairement
     * (les confusables exotiques sont rarement utilisés en phishing réel).
     */
    val CONFUSABLES: Map<Char, Char> = mapOf(
        // Cyrillique → Latin
        '\u0430' to 'a',  // а
        '\u0435' to 'e',  // е
        '\u043E' to 'o',  // о
        '\u0440' to 'p',  // р
        '\u0441' to 'c',  // с
        '\u0445' to 'x',  // х
        '\u0443' to 'y',  // у
        '\u0438' to 'u',  // и (visuellement proche)
        '\u04CF' to 'l',  // ӏ
        '\u04AB' to 's',  // ҫ
        '\u0455' to 's',  // ѕ
        '\u0458' to 'j',  // ј
        // Grec → Latin
        '\u03BF' to 'o',  // ο
        '\u03B1' to 'a',  // α
        '\u03B5' to 'e',  // ε
        '\u03BD' to 'v',  // ν
        '\u03C1' to 'p',  // ρ
        '\u03C5' to 'u',  // υ
        '\u03C7' to 'x',  // χ
        '\u03B9' to 'i',  // ι
        '\u03BC' to 'u',  // μ (visuellement proche)
        '\u03BA' to 'k',  // κ
        // Autres scripts couramment exploités
        '\u0131' to 'i',  // ı (turc)
        '\u0237' to 'j',  // ȷ
    )

    /**
     * Substitutions chiffres → lettres pour comparaison brand uniquement.
     * Utilisé par `normalizeForBrandComparison()`, PAS par
     * `normalizeConfusables()`.
     */
    private val DIGIT_TO_LETTER: Map<Char, Char> = mapOf(
        '0' to 'o',
        '1' to 'l',
        '3' to 'e',
        '4' to 'a',
        '5' to 's',
        '6' to 'b',
        '7' to 't',
        '8' to 'b',
    )

    /**
     * Normalise une chaîne en remplaçant les confusables Unicode par leur
     * équivalent latin. Utilisé AVANT match avec la blocklist.
     *
     * NE FAIT PAS de substitution chiffres → lettres.
     *
     * Exemples :
     *   "gооgle"   (cyrill.) → "google"  (match blocklist OK)
     *   "amаzon"   (cyrill.) → "amazon"  (match blocklist OK)
     *   "paypa1"   (chiffre) → "paypa1"  (PAS normalisé : 1 reste 1)
     */
    fun normalizeConfusables(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(CONFUSABLES[c] ?: c)
        }
        return sb.toString()
    }

    /**
     * Normalise une chaîne pour comparaison de brand uniquement.
     * Applique d'abord les confusables Unicode, PUIS les substitutions
     * chiffres → lettres. Utilisé par `detectBrandTyposquatting`.
     *
     * Exemples :
     *   "paypa1"   → "paypal" (1→l)
     *   "g00gle"   → "google" (0→o)
     *   "micros0ft" → "microsoft" (0→o)
     *   "arnazon"  → "arnazon" (pas de chiffre, on s'appuie sur Levenshtein)
     */
    fun normalizeForBrandComparison(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            val confusable = CONFUSABLES[c]
            if (confusable != null) {
                sb.append(confusable)
            } else {
                sb.append(DIGIT_TO_LETTER[c] ?: c)
            }
        }
        return sb.toString()
    }

    // ----------------------------------------------------------------------- //
    //  Distance de Levenshtein bornée (pour brand typosquatting)
    // ----------------------------------------------------------------------- //
    /**
     * Calcule la distance d'édition de Levenshtein entre `a` et `b`, mais
     * abandonne dès que la distance dépasse `maxDist` (optimisation : on n'a
     * pas besoin de la vraie distance, juste de savoir si elle est <= 2).
     *
     * Pour `paypa1` vs `paypal` → distance 1.
     * Pour `paypal-secure` vs `paypal` → distance >> 2 (on s'arrête tôt).
     */
    fun levenshteinBounded(a: String, b: String, maxDist: Int): Int {
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > maxDist) return maxDist + 1
        if (la == 0) return lb
        if (lb == 0) return la

        val prev = IntArray(lb + 1) { it }
        val curr = IntArray(lb + 1)

        for (i in 1..la) {
            curr[0] = i
            var rowMin = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,           // insertion
                    prev[j] + 1,               // suppression
                    prev[j - 1] + cost,        // substitution
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            // Early exit : si toute la ligne dépasse déjà maxDist, inutile de continuer.
            if (rowMin > maxDist) return maxDist + 1
            System.arraycopy(curr, 0, prev, 0, lb + 1)
        }
        return prev[lb]
    }
}
