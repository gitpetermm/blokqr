package com.blokqr.app.data
import android.content.Context
/**
 * Réglages de sécurité applicatifs, centralisés (SharedPreferences synchrones).
 *
 * Quatre options, toutes OFF par défaut (opt-in) :
 *   - verrou biométrique de l'écran Historique ;
 *   - verrou biométrique de l'application entière ;
 *   - délai de grâce avant re-verrouillage de l'application (0 = immédiat) ;
 *   - écran sécurisé (FLAG_SECURE : masque l'app dans le multitâche et bloque
 *     les captures d'écran).
 *
 * Aucune donnée sensible ici : seulement des booléens / un entier de préférence.
 */
class SecurityStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    // --- Verrou de l'écran Historique ---
    fun isHistoryLockEnabled(): Boolean = prefs.getBoolean(KEY_HISTORY_LOCK, false)
    fun setHistoryLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HISTORY_LOCK, enabled).apply()
    }
    // --- Verrou de l'application ---
    fun isAppLockEnabled(): Boolean = prefs.getBoolean(KEY_APP_LOCK, false)
    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
    }
    /** Délai de grâce avant re-verrouillage, en secondes (0, 10, 20 ou 30). */
    fun graceSeconds(): Int = prefs.getInt(KEY_GRACE, 0)
    fun setGraceSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_GRACE, seconds).apply()
    }
    // --- Écran sécurisé (FLAG_SECURE) ---
    fun isSecureScreenEnabled(): Boolean = prefs.getBoolean(KEY_SECURE, false)
    fun setSecureScreenEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SECURE, enabled).apply()
    }
    companion object {
        private const val PREFS_NAME = "security_prefs"
        private const val KEY_HISTORY_LOCK = "history_lock"
        private const val KEY_APP_LOCK = "app_lock"
        private const val KEY_GRACE = "grace_seconds"
        private const val KEY_SECURE = "secure_screen"
        /** Valeurs proposées pour le délai de grâce (secondes). */
        val GRACE_OPTIONS = listOf(0, 10, 20, 30)
    }
}
