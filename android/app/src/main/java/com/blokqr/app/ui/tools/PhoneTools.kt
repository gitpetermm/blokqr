package com.blokqr.app.ui.tools
import android.content.Context
import io.michaelrocks.libphonenumber.android.NumberParseException
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil.PhoneNumberFormat
import java.util.Locale
/** Un pays : région ISO, indicatif, drapeau emoji, nom localisé. */
data class PhoneCountry(
    val region: String,
    val code: Int,
    val flag: String,
    val name: String,
)
/** Résultat d'analyse d'un numéro : E.164 (si valide), validité, région retenue. */
data class PhoneParse(
    val e164: String?,
    val valid: Boolean,
    val region: String,
)
/**
 * Outils téléphone 100 % HORS-LIGNE (libphonenumber, métadonnées embarquées).
 * Aucune requête réseau : analyse, validation par pays, formatage, exemples et
 * liste des pays sont calculés localement.
 */
object PhoneTools {
    /** Instance à créer une fois puis à conserver (pas de getInstance sur ce port). */
    fun createUtil(context: Context): PhoneNumberUtil = PhoneNumberUtil.createInstance(context)
    /** Tous les pays pris en charge, triés par nom dans la langue [locale]. */
    fun countries(util: PhoneNumberUtil, locale: Locale): List<PhoneCountry> =
        util.supportedRegions
            .map { r ->
                PhoneCountry(
                    region = r,
                    code = util.getCountryCodeForRegion(r),
                    flag = flag(r),
                    name = displayCountry(r, locale),
                )
            }
            .sortedBy { it.name.lowercase(locale) }
    /**
     * Analyse [raw] : si international (+…), l'indicatif est détecté ; sinon
     * [defaultRegion] est utilisé. Renvoie l'E.164 si le numéro est VALIDE.
     */
    fun parse(util: PhoneNumberUtil, defaultRegion: String, raw: String): PhoneParse {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return PhoneParse(null, false, defaultRegion)
        return try {
            val number = util.parse(trimmed, defaultRegion)
            val valid = util.isValidNumber(number)
            val e164 = if (valid) util.format(number, PhoneNumberFormat.E164) else null
            val region = util.getRegionCodeForNumber(number) ?: defaultRegion
            PhoneParse(e164, valid, region)
        } catch (e: NumberParseException) {
            PhoneParse(null, false, defaultRegion)
        }
    }
    /** Exemple de numéro national pour [region] (aide au format). */
    fun example(util: PhoneNumberUtil, region: String): String? = try {
        util.getExampleNumber(region)?.let { util.format(it, PhoneNumberFormat.NATIONAL) }
    } catch (e: Exception) {
        null
    }
    /**
     * Si [raw] est un numéro international complet (+…), renvoie (région,
     * numéro national) pour séparer l'indicatif du reste ; sinon null.
     */
    fun splitInternational(util: PhoneNumberUtil, raw: String): Pair<String, String>? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("+")) return null
        return try {
            val number = util.parse(trimmed, null)
            val region = util.getRegionCodeForNumber(number) ?: return null
            region to util.format(number, PhoneNumberFormat.NATIONAL)
        } catch (e: NumberParseException) {
            null
        }
    }
    /** Nom de pays localisé sans le constructeur Locale(String,String) déprécié. */
    private fun displayCountry(region: String, locale: Locale): String =
        runCatching { Locale.Builder().setRegion(region).build().getDisplayCountry(locale) }
            .getOrNull()
            ?.ifBlank { region }
            ?: region
    /** Drapeau emoji à partir du code ISO (deux indicateurs régionaux Unicode). */
    fun flag(region: String): String {
        if (region.length != 2) return "\uD83C\uDFF3"
        val up = region.uppercase(Locale.ROOT)
        val a = 0x1F1E6 + (up[0].code - 'A'.code)
        val b = 0x1F1E6 + (up[1].code - 'A'.code)
        return String(Character.toChars(a)) + String(Character.toChars(b))
    }
}
