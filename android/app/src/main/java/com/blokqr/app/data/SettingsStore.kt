package com.blokqr.app.data
import android.content.Context
import android.content.res.Configuration
import com.blokqr.app.ui.theme.ThemeChoice
import java.util.Locale
/**
 * Préférences locales (thème + langue + onboarding), stockées en SharedPreferences.
 *
 * SharedPreferences (et non DataStore) car la langue doit être lue de façon
 * SYNCHRONE très tôt dans le cycle de vie (Activity.attachBaseContext), avant
 * toute coroutine. Le changement de thème/langue déclenche un Activity.recreate()
 * qui relit ces valeurs : pas besoin de flux réactif.
 */
class SettingsStore(context: Context) {
    // Contexte passé directement (et non applicationContext) : sûr dès
    // attachBaseContext. Le fichier de préférences est global à l'app de toute façon.
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun themeChoice(): ThemeChoice = ThemeChoice.fromKey(prefs.getString(KEY_THEME, null))
    fun setThemeChoice(choice: ThemeChoice) {
        prefs.edit().putString(KEY_THEME, choice.key).apply()
    }
    /** Étiquette BCP-47 ("fr", "en") ou "" pour suivre la langue du système. */
    fun languageTag(): String = prefs.getString(KEY_LANG, "") ?: ""
    fun setLanguageTag(tag: String) {
        prefs.edit().putString(KEY_LANG, tag).apply()
    }
    /** Onboarding déjà vu ? (affiché une seule fois, au premier lancement). */
    fun onboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING, false)
    fun setOnboardingDone(done: Boolean = true) {
        prefs.edit().putBoolean(KEY_ONBOARDING, done).apply()
    }
    /**
     * Reconnaissance LOCALE des codes-barres produits (EAN/UPC) directement au
     * scan : le scanner affiche la provenance (sans analyse de sécurité, sans
     * quota, sans réseau) au lieu de traiter le numéro comme une destination.
     * Activée par défaut.
     */
    fun productRecognitionEnabled(): Boolean = prefs.getBoolean(KEY_PRODUCT, true)
    fun setProductRecognitionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRODUCT, enabled).apply()
    }
    /**
     * Recherche EN LIGNE du nom du produit (Open Food Facts) à partir du GTIN.
     * ACTIVÉE par défaut (choix produit). Le GTIN (identifiant produit, non
     * personnel) est le seul élément transmis ; l'utilisateur peut désactiver.
     */
    fun productNameLookupEnabled(): Boolean = prefs.getBoolean(KEY_PRODUCT_NAME, true)
    fun setProductNameLookupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRODUCT_NAME, enabled).apply()
    }
    private companion object {
        const val PREFS = "blokqr_settings"
        const val KEY_THEME = "theme_choice"
        const val KEY_LANG = "language_tag"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_PRODUCT = "product_recognition"
        const val KEY_PRODUCT_NAME = "product_name_lookup"
    }
}
/** Applique la langue choisie en enveloppant le Context de l'Activity. */
object LocaleHelper {
    fun wrap(base: Context, languageTag: String): Context {
        if (languageTag.isBlank()) return base // suivre le système
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}