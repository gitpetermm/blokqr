package com.blokqr.app.scanner

/**
 * Extraction best-effort d'une ou plusieurs URL depuis un texte OCR (bruité).
 *
 * Le résultat est PRÉ-REMPLI dans un champ ÉDITABLE : l'utilisateur corrige si
 * besoin avant l'analyse. On privilégie une URL avec schéma explicite, sinon un
 * domaine « nu » que l'on préfixe en https://. La normalisation fine (suivi de
 * redirections, k-anonymat) reste assurée plus loin par UrlNormalizer + backend.
 *
 * POURQUOI cette version : l'OCR insère quasi systématiquement des espaces À
 * L'INTÉRIEUR des URL (autour de « . / : ? = » …) et coupe les URL en fin de
 * ligne. Compresser les blancs ne suffit donc PAS : il faut RETIRER les espaces
 * collés à la ponctuation d'URL et recoller un schéma espacé. Sans ça, l'URL est
 * tronquée/déformée et l'utilisateur doit tout retaper.
 *
 * Aucune E/S, aucune dépendance Android : pur Kotlin, donc testable en JVM.
 */
object UrlTextExtractor {

    // Hôte (labels alphanum/tirets 1..63 + TLD alpha 2..24), port et chemin
    // optionnels, schéma http(s) optionnel. Le chemin court jusqu'au prochain
    // blanc — d'où l'importance d'avoir retiré les espaces internes AVANT.
    private val URL_RE = Regex(
        """(?i)((?:https?://)?(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}(?::\d{2,5})?(?:[/?#]\S*)?)"""
    )

    private val WS_RE = Regex("""\s+""")
    // Recolle un schéma espacé par l'OCR : « https : / / » -> « https:// ».
    private val SCHEME_RE = Regex("""(?i)\bhttps?\b\s*:\s*/\s*/""")
    // Retire les espaces que l'OCR colle AUTOUR de la ponctuation d'URL.
    // En prose normale on n'a pas « mot . mot » (espaces autour d'un point) :
    // ce sont des artefacts OCR. Les vrais mots restent séparés par leur espace.
    private val PUNCT_RE = Regex("""\s*([./:?#=&@~%_\-])\s*""")

    // Ponctuation/symboles que l'OCR colle souvent en fin d'URL.
    private val TRAILING = charArrayOf('.', ',', ';', ':', ')', ']', '}', '"', '\'', '>', '<', '!', '?', '|')

    // Pour un domaine NU (sans schéma, sans /, sans www), on n'accepte qu'un TLD
    // courant : évite de prendre « Bonjour.Monde » d'une phrase pour une URL.
    private val COMMON_TLDS = setOf(
        "com", "net", "org", "io", "co", "fr", "eu", "gov", "edu", "app", "dev",
        "info", "biz", "me", "uk", "de", "es", "it", "be", "ch", "ca", "us", "tv",
        "xyz", "online", "site", "tech", "store", "ai", "cloud", "pro", "name",
        "mobi", "cg"
    )

    /**
     * Renvoie la meilleure URL candidate (schéma garanti) ou null si rien de
     * plausible n'est détecté. Signature inchangée : remplacement direct.
     */
    fun extract(ocrText: String): String? = extractCandidates(ocrText).firstOrNull()

    /**
     * Renvoie TOUTES les URL candidates plausibles, dédupliquées et classées
     * (schéma explicite d'abord, puis la plus longue/complète). Utile pour
     * proposer des alternatives dans l'UI (l'utilisateur choisit/édite).
     */
    fun extractCandidates(ocrText: String): List<String> {
        if (ocrText.isBlank()) return emptyList()
        val text = normalize(ocrText)

        val found = URL_RE.findAll(text)
            .map { clean(it.value) }
            .filter { isPlausible(it) }
            .toList()

        // Déduplication insensible à la casse, en conservant l'ordre d'apparition.
        val seen = HashSet<String>()
        val uniq = ArrayList<String>()
        for (c in found) if (seen.add(c.lowercase())) uniq.add(c)

        // Classement : schéma explicite prioritaire, puis longueur décroissante.
        uniq.sortWith(compareBy({ !hasScheme(it) }, { -it.length }))

        return uniq.map { if (hasScheme(it)) it else "https://$it" }
    }

    private fun normalize(raw: String): String {
        var t = raw.replace('\u00A0', ' ')        // espaces insécables -> espace
        t = WS_RE.replace(t, " ").trim()          // compresse tous les blancs (dont \n)
        t = SCHEME_RE.replace(t) { m ->           // recolle « https : / / » -> « https:// »
            m.value.substringBefore(':').trim().lowercase() + "://"
        }
        t = PUNCT_RE.replace(t, "$1")             // retire les espaces autour de . / : ? = …
        return t
    }

    private fun clean(raw: String): String {
        var s = raw.trim()
        while (s.isNotEmpty() && s.last() in TRAILING) s = s.dropLast(1)
        return s
    }

    private fun hasScheme(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    private fun isPlausible(s: String): Boolean {
        val body = if (s.contains("://")) s.substringAfter("://") else s
        val host = body.substringBefore('/').substringBefore('?')
            .substringBefore('#').substringBefore(':')
        if (!host.contains('.') || host.length < 4) return false
        if (hasScheme(s)) return true                 // schéma explicite -> on accepte
        if (s.startsWith("www.", ignoreCase = true)) return true
        if (body.contains('/')) return true           // présence d'un chemin -> plausible
        val tld = host.substringAfterLast('.').lowercase()
        return tld in COMMON_TLDS                      // domaine nu : TLD courant exigé
    }
}