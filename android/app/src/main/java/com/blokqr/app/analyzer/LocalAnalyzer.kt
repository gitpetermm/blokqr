package com.blokqr.app.analyzer

import com.blokqr.app.model.AnalysisReportDto
import com.blokqr.app.model.SignalDto
import com.blokqr.app.model.Verdict
import com.blokqr.app.model.VerifiedResult
import java.util.Locale

/**
 * Analyse LEXICALE locale (100 % hors-ligne) — mode dégradé V2.
 *
 * Utilisée quand l'analyse serveur signée n'est pas disponible :
 *   - Quota Free épuisé (analyse locale comme filet de sécurité).
 *   - Réseau coupé / serveur indisponible (cf. Phase 3 UX).
 *   - Provisioning HMAC pas encore terminé (cf. Phase 3 UX).
 *
 * Cette analyse n'ouvre AUCUNE connexion réseau, ne suit AUCUNE redirection,
 * et ne produit AUCUN verdict signé.
 *
 * ⚠️ GARDE-FOU DE SÉCURITÉ FONDAMENTAL :
 *   - signatureVerified = false TOUJOURS (rien n'est vérifié cryptographiquement).
 *   - Le verdict le plus favorable possible est UNKNOWN (« Non vérifié »), JAMAIS
 *     SAFE. Une absence de signal lexical ne signifie pas « sûr » : ça signifie
 *     « rien détecté localement, sans vérification serveur ». L'UI doit le dire
 *     explicitement (badge « Analyse locale — non vérifiée »).
 *   - Si des signaux lexicaux forts sont détectés (IP littérale, homoglyphes,
 *     identifiants intégrés, match blocklist signée…), on RELÈVE le verdict :
 *       * jusqu'à DANGEROUS pour les signaux forts non confirmés
 *       * jusqu'à MALICIOUS UNIQUEMENT si match blocklist signée (consensus
 *         multi-sources verifié cryptographiquement).
 *
 * Nouveautés V2 (par rapport à V1) :
 *   - Lookup contre la BlocklistManager (3 couches : bundled/cached/refresh)
 *     → poids 90, peut atteindre MALICIOUS
 *   - Détection des schemes dangereux (intent://, javascript:, data:text/html)
 *     → poids 60, signal d'attaque évidente
 *   - Détection de typosquatting de marques connues (paypa1, g00gle, arnazon…)
 *     → poids 50, via distance Levenshtein bornée + confusables normalisés
 *   - Élargissement des homoglyphes (Cyrillique + Grec étendus)
 *   - Élargissement de la liste de raccourcisseurs (~30 services)
 *
 * Les codes de signaux émis correspondent à ceux déjà connus de
 * ResultScreen.signalTitle() : ils sont donc affichés traduits.
 */
object LocalAnalyzer {

    /** Indique si un payload brut ressemble à une URL analysable lexicalement. */
    fun looksLikeUrl(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true) ||
            isDangerousScheme(t)
    }

    /**
     * Analyse lexicale d'un payload. Retourne TOUJOURS un VerifiedResult avec
     * signatureVerified=false. Le type est "url" si ça ressemble à une URL,
     * sinon "text" (rendu neutre dans l'UI).
     */
    fun analyze(raw: String, symbology: String?): VerifiedResult {
        val trimmed = raw.trim()

        // Cas 1 : schema dangereux explicite (intent://, javascript:, data:...).
        // Détecté avant même le test "http(s)" parce que ces schemes sont
        // dangereux par nature.
        if (isDangerousScheme(trimmed)) {
            return buildDangerousSchemeResult(trimmed)
        }

        val isUrl = looksLikeUrl(trimmed)
        if (!isUrl) {
            // Contenu non navigable : on renvoie un rapport neutre.
            return VerifiedResult(
                verdict = Verdict.NEUTRAL,
                score = 0,
                report = AnalysisReportDto(
                    payloadType = "text",
                    displayedValue = trimmed,
                ),
                signatureVerified = false,
                rawType = "text",
            )
        }

        val signals = mutableListOf<SignalDto>()
        var score = 0
        var blocklistHit = false  // flag pour autoriser le verdict MALICIOUS

        // --- Décomposition basique de l'URL (sans réseau) ---
        val host = extractHost(trimmed)
        val hostNoPort = host.substringBefore(':')
        // Domaine normalisé (confusables remplacés) pour lookup blocklist
        // et comparaison brand : "gооgle.com" (cyrillique) -> "google.com".
        val hostNormalized = LocalThreatData.normalizeConfusables(hostNoPort.lowercase())

        // 0) Lookup BLOCKLIST SIGNÉE (priorité maximale).
        //    Si la cible est dans la blocklist signée par le serveur, c'est
        //    quasi-certainement malveillant : on alerte fort.
        if (BlocklistManager.contains(hostNormalized)) {
            signals += signal("embedded_blocklist_hit", "high", 90)
            score += 90
            blocklistHit = true
        }

        // 1) Identifiants intégrés dans l'URL (http://user:pass@host).
        if (hasEmbeddedCredentials(trimmed)) {
            signals += signal("embedded_credentials", "high", 45)
            score += 45
        }

        // 2) Hôte = adresse IP littérale.
        if (isIpLiteral(hostNoPort)) {
            signals += signal("ip_literal_host", "high", 30)
            score += 30
        }

        // 3) Homoglyphes / IDN.
        if (hostNoPort.contains("xn--", ignoreCase = true)) {
            signals += signal("punycode_idn", "medium", 20)
            score += 20
        }
        if (hasNonAsciiHost(hostNoPort)) {
            signals += signal("non_ascii_host", "medium", 18)
            score += 18
        }
        if (hasMixedScriptHomoglyph(hostNoPort)) {
            signals += signal("idn_homoglyph", "high", 28)
            score += 28
        }

        // 4) Brand typosquatting (V2) : détecte les variantes de marques
        //    connues par confusables Unicode + substitutions chiffres + Levenshtein.
        //    Ex: "paypa1.com" (1→l), "g00gle.com" (0→o), "arnazon.com" (Levenshtein).
        //    NOTE : on utilise une normalisation SPÉCIFIQUE qui inclut les
        //    substitutions chiffres (différente de celle pour blocklist).
        val matchedBrand = detectBrandTyposquatting(hostNoPort.lowercase())
        if (matchedBrand != null) {
            signals += signal("brand_typosquatting", "high", 50)
            score += 50
        }

        // 5) TLD à risque (V2 : liste étendue).
        if (hasHighRiskTldExtended(hostNoPort)) {
            signals += signal("high_risk_tld", "medium", 15)
            score += 15
        }

        // 6) Sous-domaines excessifs.
        if (countSubdomains(hostNoPort) >= 4) {
            signals += signal("excessive_subdomains", "medium", 15)
            score += 15
        }

        // 7) Port inhabituel.
        val port = host.substringAfter(':', "")
        if (port.isNotEmpty() && port != "80" && port != "443") {
            signals += signal("unusual_port", "low", 10)
            score += 10
        }

        // 8) Mots-clés de phishing.
        if (hasPhishingKeywords(trimmed)) {
            signals += signal("phishing_keywords", "medium", 18)
            score += 18
        }

        // 9) Entropie élevée du domaine.
        if (hostNoPort.isNotEmpty() && shannonEntropy(hostNoPort) > 4.0) {
            signals += signal("high_entropy_domain", "low", 12)
            score += 12
        }

        // 10) Raccourcisseur d'URL (V2 : liste étendue).
        if (isUrlShortenerExtended(hostNoPort)) {
            signals += signal("url_shortener", "medium", 16)
            score += 16
        }

        // 11) Cible exécutable (.apk, .exe, .scr…).
        if (hasExecutableTarget(trimmed)) {
            signals += signal("executable_target", "high", 30)
            score += 30
        }

        // --- Verdict local ---
        // Règle : MALICIOUS UNIQUEMENT si match blocklist signée (consensus
        // multi-sources cryptographiquement vérifié). Sinon DANGEROUS au max,
        // sauf si aucun signal -> UNKNOWN ("non vérifié").
        val verdict = when {
            blocklistHit -> Verdict.MALICIOUS
            score >= LOCAL_DANGER_THRESHOLD -> Verdict.DANGEROUS
            else -> Verdict.UNKNOWN
        }
        val boundedScore = score.coerceIn(0, 100)

        return VerifiedResult(
            verdict = verdict,
            score = boundedScore,
            report = AnalysisReportDto(
                payloadType = "url",
                displayedValue = trimmed,
                originalTarget = trimmed,
                finalTarget = trimmed,           // pas de résolution hors-ligne
                signals = signals.sortedByDescending { it.weight },
            ),
            signatureVerified = false,           // JAMAIS vérifié en local
            rawType = "url",
        )
    }

    // ----------------------------------------------------------------------- //
    //  Helpers V2 (nouveaux)
    // ----------------------------------------------------------------------- //

    /** Détecte un scheme dangereux explicite (intent://, javascript:, data:...). */
    private fun isDangerousScheme(raw: String): Boolean {
        val lower = raw.trim().lowercase()
        return LocalThreatData.DANGEROUS_SCHEMES.any { lower.startsWith(it) }
    }

    /** Construit le résultat dégradé pour un scheme dangereux. */
    private fun buildDangerousSchemeResult(raw: String): VerifiedResult {
        val signals = listOf(
            signal("dangerous_scheme", "high", 60),
        )
        return VerifiedResult(
            verdict = Verdict.DANGEROUS,
            score = 60,
            report = AnalysisReportDto(
                payloadType = "url",
                displayedValue = raw,
                originalTarget = raw,
                signals = signals,
            ),
            signatureVerified = false,
            rawType = "url",
        )
    }

    /**
     * Détecte si l'hôte ressemble à une marque connue : distance Levenshtein
     * 1 ou 2 vs une brand de référence, APRÈS normalisation Unicode +
     * substitutions chiffres → lettres (`0→o`, `1→l`).
     *
     * IMPORTANT : la normalisation utilisée ici est PLUS AGRESSIVE que celle
     * utilisée pour le lookup blocklist (qui ne touche pas aux chiffres pour
     * éviter les faux positifs). Voir `normalizeForBrandComparison()` vs
     * `normalizeConfusables()` dans LocalThreatData.
     *
     * Retourne le nom de la brand matchée, ou null si aucune.
     *
     * Exemples qui matchent :
     *   - paypa1.com         → paypal  (dist 0 après normalisation 1→l)
     *   - amаzon.com         → amazon  (dist 0 après normalisation cyrillique)
     *   - g00gle.com         → google  (dist 0 après normalisation 0→o)
     *   - micros0ft.com      → microsoft (dist 0 après normalisation 0→o)
     *   - arnazon.com        → amazon  (dist 2 : r+n → m)
     *
     * Exemples qui NE matchent pas :
     *   - paypal.com         (vrai domaine, dist 0 après normalisation = brand exact → filtré)
     *   - paypal-secure.com  (dist 7, trop loin)
     */
    private fun detectBrandTyposquatting(hostLowercase: String): String? {
        // Extrait le label principal (sans TLD ni sous-domaine).
        // Ex: "login.paypa1.com" -> "paypa1"
        val labels = hostLowercase.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return null
        val mainLabel = labels[labels.size - 2]

        // Normalise APRÈS extraction du label (substitutions agressives pour
        // détection visuelle de typosquat).
        val normalizedLabel = LocalThreatData.normalizeForBrandComparison(mainLabel)

        // Si APRÈS normalisation on a EXACTEMENT le nom de la brand, c'est :
        //  - soit le vrai domaine (paypal.com) -> on ne signale pas
        //  - soit un typosquat parfaitement déguisé (paypa1.com -> paypal après 1→l)
        //
        // Pour distinguer : si la chaîne ORIGINALE était déjà le brand exact,
        // c'est le vrai domaine. Sinon, c'est un typosquat parfait → SIGNALE.
        if (normalizedLabel in LocalThreatData.TYPOSQUAT_BRANDS) {
            return if (mainLabel == normalizedLabel) {
                null  // Vrai domaine (paypal, google, etc.)
            } else {
                normalizedLabel  // Typosquat parfait après normalisation
            }
        }

        // Sinon, distance Levenshtein 1 ou 2 max sur la version normalisée.
        for (brand in LocalThreatData.TYPOSQUAT_BRANDS) {
            // Heuristique de performance : ignorer si trop courts ou trop différents
            // en longueur (la distance Levenshtein bornée le fait aussi, mais on
            // évite l'appel pour la majorité des cas).
            if (kotlin.math.abs(normalizedLabel.length - brand.length) > 2) continue
            if (brand.length < 4) continue  // évite false positives sur "n26", "lcl"
            val maxDist = if (brand.length <= 6) 1 else 2
            val dist = LocalThreatData.levenshteinBounded(normalizedLabel, brand, maxDist)
            if (dist in 1..maxDist) return brand
        }
        return null
    }

    /** Liste TLDs étendue (V2). */
    private fun hasHighRiskTldExtended(host: String): Boolean {
        val lower = host.lowercase(Locale.ROOT)
        // Test des TLDs de premier niveau classiques.
        val tld = lower.substringAfterLast('.', "")
        if (tld in LocalThreatData.HIGH_RISK_TLDS_EXTENDED) return true
        // Test des TLDs composés (pages.dev, vercel.app, etc.).
        // On vérifie si le domaine SE TERMINE par un de ces patterns.
        for (composed in LocalThreatData.HIGH_RISK_TLDS_EXTENDED) {
            if (composed.contains('.') && lower.endsWith(".$composed")) return true
            if (composed.contains('.') && lower == composed) return true
        }
        return false
    }

    /** Liste raccourcisseurs étendue (V2). */
    private fun isUrlShortenerExtended(host: String): Boolean {
        val h = host.removePrefix("www.").lowercase(Locale.ROOT)
        return h in LocalThreatData.URL_SHORTENERS_EXTENDED
    }

    // ----------------------------------------------------------------------- //
    //  Helpers V1 (préservés, identiques)
    // ----------------------------------------------------------------------- //

    private fun signal(code: String, severity: String, weight: Int) = SignalDto(
        code = code,
        title = code,            // fallback ; l'UI traduit via signalTitle(code)
        severity = severity,
        weight = weight,
        source = "local_lexical",
    )

    private fun extractHost(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@')
    }

    private fun hasEmbeddedCredentials(url: String): Boolean {
        val afterScheme = url.substringAfter("://", "")
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.contains('@')
    }

    private fun isIpLiteral(host: String): Boolean {
        val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        if (ipv4.matches(host)) return true
        if (host.startsWith("[") && host.endsWith("]")) return true
        return false
    }

    private fun hasNonAsciiHost(host: String): Boolean =
        host.any { it.code > 127 }

    private fun hasMixedScriptHomoglyph(host: String): Boolean {
        var hasLatin = false
        var hasOther = false
        for (ch in host) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') hasLatin = true
            else if (ch.code in 0x0400..0x04FF || ch.code in 0x0370..0x03FF) hasOther = true
        }
        return hasLatin && hasOther
    }

    private fun countSubdomains(host: String): Int {
        val labels = host.split('.').filter { it.isNotEmpty() }
        return (labels.size - 2).coerceAtLeast(0)
    }

    private fun hasPhishingKeywords(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return PHISHING_KEYWORDS.any { lower.contains(it) }
    }

    private fun hasExecutableTarget(url: String): Boolean {
        val path = url.substringAfter("://", "").substringAfter('/', "")
            .substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return EXECUTABLE_EXTENSIONS.any { path.endsWith(it) }
    }

    /** Entropie de Shannon. */
    private fun shannonEntropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val freq = HashMap<Char, Int>()
        for (c in s) freq[c] = (freq[c] ?: 0) + 1
        val len = s.length.toDouble()
        var entropy = 0.0
        for ((_, count) in freq) {
            val p = count / len
            entropy -= p * (Math.log(p) / Math.log(2.0))
        }
        return entropy
    }

    // ----------------------------------------------------------------------- //
    //  Constantes (préservées + élargies V2)
    // ----------------------------------------------------------------------- //

    private const val LOCAL_DANGER_THRESHOLD = 40

    private val PHISHING_KEYWORDS = listOf(
        "verify", "verification", "account", "secure", "login", "signin",
        "update", "confirm", "banking", "wallet", "suspended", "unlock",
        "verifier", "compte", "connexion", "securise", "mot-de-passe", "password",
        // V2 : élargissement
        "validate", "reactivate", "restore", "recover", "alert", "security",
        "authentification", "authenticate", "support-team",
    )

    private val EXECUTABLE_EXTENSIONS = listOf(
        ".apk", ".exe", ".scr", ".msi", ".bat", ".cmd", ".jar", ".dmg",
        ".pkg", ".deb", ".sh", ".vbs", ".ps1",
        // V2 : élargissement
        ".com", ".pif", ".hta", ".cpl", ".lnk",
    )
}
