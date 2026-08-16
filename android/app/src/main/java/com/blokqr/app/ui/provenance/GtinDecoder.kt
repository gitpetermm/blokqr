package com.blokqr.app.ui.provenance
import java.util.Locale
/** Informations décodées d'un code-barres produit (GTIN). */
data class GtinInfo(
    val gtin: String,          // GTIN normalisé (13 ou 8 chiffres)
    val format: String,        // "GTIN-13", "GTIN-12 (UPC-A)", "GTIN-8", "UPC-E"
    val validCheck: Boolean,   // clé de contrôle (modulo 10) correcte
    val prefix: Int?,          // préfixe GS1 (3 chiffres) ou null (GTIN-8)
    val issuer: Gs1Entry?,     // organisation GS1 émettrice ou null
)
/**
 * Décodeur GTIN 100 % HORS-LIGNE : normalise EAN/UPC en GTIN-13, valide la clé
 * de contrôle (modulo 10) et identifie l'organisation GS1 émettrice.
 * AUCUNE analyse de sécurité : reconnaissance et décodage uniquement.
 */
object GtinDecoder {
    /** Vrai si la symbologie correspond à un code-barres PRODUIT (EAN/UPC). */
    fun isProductSymbology(symbology: String): Boolean {
        val s = symbology.lowercase(Locale.ROOT)
        return s.contains("ean") || s.contains("upc")
    }
    fun decode(rawValue: String, symbology: String): GtinInfo? {
        val d = rawValue.filter { it.isDigit() }
        return when {
            symbology.contains("upc_e", ignoreCase = true) ||
                symbology.contains("upce", ignoreCase = true) -> fromUpcE(d)
            d.length == 13 -> fromGtin13(d, "GTIN-13")
            d.length == 12 -> fromGtin13("0$d", "GTIN-12 (UPC-A)")
            d.length == 8 -> fromGtin8(d)
            else -> null
        }
    }
    private fun fromGtin13(g: String, format: String): GtinInfo? {
        if (g.length != 13) return null
        val prefix = g.substring(0, 3).toInt()
        return GtinInfo(g, format, validCheck(g), prefix, Gs1Prefixes.lookup(prefix))
    }
    private fun fromGtin8(d: String): GtinInfo? {
        if (d.length != 8) return null
        // Un GTIN-8 n'encode pas le préfixe pays de la même façon -> pas de lookup.
        return GtinInfo(d, "GTIN-8 (EAN-8)", validCheck(d), null, null)
    }
    private fun fromUpcE(d: String): GtinInfo? {
        val upca = expandUpcE(d) ?: return null
        return fromGtin13("0$upca", "UPC-E")
    }
    /** Étend un UPC-E en UPC-A (12 chiffres), clé de contrôle recalculée. */
    private fun expandUpcE(input: String): String? {
        val d = input.filter { it.isDigit() }
        val ns: Char
        val m: String
        when (d.length) {
            6 -> { ns = '0'; m = d }
            7 -> { ns = d[0]; m = d.substring(1) }
            8 -> { ns = d[0]; m = d.substring(1, 7) }
            else -> return null
        }
        if (m.length != 6 || (ns != '0' && ns != '1')) return null
        val body = when (m[5]) {
            '0', '1', '2' -> "${m[0]}${m[1]}${m[5]}0000${m[2]}${m[3]}${m[4]}"
            '3' -> "${m[0]}${m[1]}${m[2]}00000${m[3]}${m[4]}"
            '4' -> "${m[0]}${m[1]}${m[2]}${m[3]}00000${m[4]}"
            else -> "${m[0]}${m[1]}${m[2]}${m[3]}${m[4]}0000${m[5]}"
        }
        val without = "$ns$body"
        return "$without${checkDigit(without)}"
    }
    /** Clé de contrôle GS1 (modulo 10) sur les chiffres SANS la clé. */
    private fun checkDigit(data: String): Int {
        var sum = 0
        data.reversed().forEachIndexed { i, c ->
            val n = c - '0'
            sum += if (i % 2 == 0) n * 3 else n
        }
        return (10 - sum % 10) % 10
    }
    /** Vrai si le dernier chiffre du GTIN est la bonne clé de contrôle. */
    private fun validCheck(gtin: String): Boolean {
        if (gtin.length < 2) return false
        return checkDigit(gtin.dropLast(1)) == (gtin.last() - '0')
    }
}
