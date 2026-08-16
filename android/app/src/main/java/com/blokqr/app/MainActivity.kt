package com.blokqr.app
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.blokqr.app.data.LocaleHelper
import com.blokqr.app.data.SecurityStore
import com.blokqr.app.data.SettingsStore
import com.blokqr.app.ui.AppNavigation
import com.blokqr.app.ui.MainTab
import com.blokqr.app.ui.onboarding.OnboardingScreen
import com.blokqr.app.ui.security.AppLockGate
import com.blokqr.app.ui.splash.BrandRevealScreen
import com.blokqr.app.ui.theme.BlokQrTheme
/**
 * FragmentActivity (et non ComponentActivity) car BiometricPrompt exige une
 * FragmentActivity pour afficher sa boîte de dialogue système. FragmentActivity
 * hérite de ComponentActivity : setContent { } et tout le reste sont inchangés.
 */
class MainActivity : FragmentActivity() {
    // Intent entrant en attente : lien PARTAGÉ à analyser, onglet demandé par un
    // raccourci, et/ou ouverture de l'analyse d'URL (widget « Vérifier un lien »).
    // Consommés par AppNavigation quand l'UI est prête (après déverrouillage/
    // onboarding), puis remis à null/false.
    private val pendingUrl = mutableStateOf<String?>(null)
    private val pendingTab = mutableStateOf<MainTab?>(null)
    private val pendingAnalyzeUrl = mutableStateOf(false)
    /** Applique la langue choisie avant la création des vues. */
    override fun attachBaseContext(newBase: Context) {
        val lang = SettingsStore(newBase).languageTag()
        super.attachBaseContext(LocaleHelper.wrap(newBase, lang))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash Screen API (core-splashscreen) : à appeler AVANT super.onCreate().
        // Le système affiche l'icône sur le fond du thème Theme.BlokQr.Splash, puis
        // bascule sur le thème applicatif (postSplashScreenTheme) une fois prêt.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Edge-to-edge : le contenu s'étend derrière les barres système
        // (transparentes). Le contraste des icônes est piloté par BlokQrTheme
        // selon le thème résolu ; les écrans appliquent déjà leurs insets.
        enableEdgeToEdge()
        // Anti-tapjacking : ignore les évènements tactiles lorsque la fenêtre est
        // masquée par une surface d'une autre application (overlay) — empêche qu'un
        // faux recouvrement piège l'utilisateur (ex. tap sur « ouvrir le lien »).
        window.decorView.filterTouchesWhenObscured = true
        // Écran sécurisé (option) : masque l'app dans le multitâche et bloque les
        // captures d'écran. Appliqué au démarrage selon le réglage enregistré.
        val security = SecurityStore(this)
        if (security.isSecureScreenEnabled()) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        // Lit l'intent de lancement (partage entrant / raccourci / widget).
        handleIntent(intent)
        setContent {
            val store = remember { SettingsStore(this) }
            // Réglages de verrou lus au lancement (toute modification s'applique au
            // prochain démarrage de l'app).
            val appLockEnabled = remember { security.isAppLockEnabled() }
            val graceSeconds = remember { security.graceSeconds() }
            // État hissé : changer de thème recompose sans redémarrer l'activité.
            var theme by remember { mutableStateOf(store.themeChoice()) }
            // Onboarding affiché une seule fois (premier lancement), rejouable
            // depuis l'écran « À propos ».
            var onboardingDone by remember { mutableStateOf(store.onboardingDone()) }
            // Écran de marque animé au démarrage (skippable, respecte « réduire
            // les animations »), puis on entre dans l'app.
            var showBrand by remember { mutableStateOf(true) }
            BlokQrTheme(choice = theme) {
                Surface(Modifier.fillMaxSize()) {
                    if (showBrand) {
                        BrandRevealScreen(onFinish = { showBrand = false })
                    } else {
                        AppLockGate(
                            enabled = appLockEnabled,
                            graceSeconds = graceSeconds,
                            title = stringResource(R.string.app_lock_title),
                            subtitle = stringResource(R.string.app_lock_subtitle),
                            unlockLabel = stringResource(R.string.history_unlock),
                            cancelLabel = stringResource(R.string.action_back),
                        ) {
                            if (!onboardingDone) {
                                OnboardingScreen(
                                    onFinish = {
                                        store.setOnboardingDone()
                                        onboardingDone = true
                                    },
                                )
                            } else {
                                AppNavigation(
                                    onThemeChange = { choice ->
                                        theme = choice
                                        store.setThemeChoice(choice)
                                    },
                                    onReplayOnboarding = {
                                        store.setOnboardingDone(false)
                                        onboardingDone = false
                                    },
                                    pendingUrl = pendingUrl.value,
                                    onUrlConsumed = { pendingUrl.value = null },
                                    pendingTab = pendingTab.value,
                                    onTabConsumed = { pendingTab.value = null },
                                    openUrlAnalyzer = pendingAnalyzeUrl.value,
                                    onUrlAnalyzerConsumed = { pendingAnalyzeUrl.value = false },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    /**
     * Intent reçu alors que l'activité tourne déjà (launchMode singleTask) :
     * nouveau partage ou raccourci. On met à jour l'intent courant et l'état.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    /** Extrait un éventuel lien partagé, un onglet de raccourci, ou l'analyse d'URL. */
    private fun handleIntent(intent: Intent?) {
        extractSharedUrl(intent)?.let { pendingUrl.value = it }
        extractShortcutTab(intent)?.let { pendingTab.value = it }
        if (intent?.action == ACTION_SHORTCUT_ANALYZE_URL) pendingAnalyzeUrl.value = true
    }
    /**
     * Lien PARTAGÉ (« Partager -> BlokQR », ACTION_SEND text/plain). Si le texte
     * partagé contient une URL http(s), on l'extrait ; sinon on transmet le texte
     * tel quel (l'analyse gère l'URL). Aucun autre type d'intent n'est accepté.
     */
    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return null
        return Regex("""https?://\S+""").find(text)?.value ?: text
    }
    /** Onglet demandé par un raccourci d'app (appui long sur l'icône). */
    private fun extractShortcutTab(intent: Intent?): MainTab? = when (intent?.action) {
        ACTION_SHORTCUT_CREATE -> MainTab.CREATE
        ACTION_SHORTCUT_SCAN -> MainTab.SCANNER
        else -> null
    }
    // PUBLIC : le widget (ScanWidgetProvider) et la tuile (ScanTileService)
    // référencent ces actions pour ouvrir l'écran voulu via un intent explicite.
    companion object {
        const val ACTION_SHORTCUT_SCAN = "com.blokqr.app.action.SHORTCUT_SCAN"
        const val ACTION_SHORTCUT_CREATE = "com.blokqr.app.action.SHORTCUT_CREATE"
        const val ACTION_SHORTCUT_ANALYZE_URL = "com.blokqr.app.action.SHORTCUT_ANALYZE_URL"
    }
}
