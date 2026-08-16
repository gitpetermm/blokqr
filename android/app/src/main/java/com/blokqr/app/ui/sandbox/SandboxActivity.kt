package com.blokqr.app.ui.sandbox
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.blokqr.app.data.SettingsStore
import com.blokqr.app.ui.theme.BlokQrTheme
import com.blokqr.app.ui.theme.ThemeChoice
/**
 * Hôte du bac à sable WebView, exécuté dans un PROCESS SÉPARÉ (`:sandbox`).
 * Ne détient AUCUN secret (install_id, HMAC, entitlement restent côté process
 * principal). FLAG_SECURE anti-capture. Voir SandboxBrowserScreen.
 */
class SandboxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { WebView.setDataDirectorySuffix(WEBVIEW_DATA_SUFFIX) }
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        // Verdict à risque : on autorise le téléchargement mais avec avertissements.
        val riskyDownloads = intent?.getBooleanExtra(EXTRA_RISKY_DOWNLOADS, false) ?: false
        val themeChoice = runCatching { SettingsStore(this).themeChoice() }
            .getOrDefault(ThemeChoice.SYSTEM)
        setContent {
            BlokQrTheme(choice = themeChoice) {
                SandboxBrowserScreen(
                    url = url,
                    onClose = { finish() },
                    riskyDownloads = riskyDownloads,
                )
            }
        }
    }
    companion object {
        private const val EXTRA_URL = "blokqr.sandbox.url"
        private const val EXTRA_RISKY_DOWNLOADS = "blokqr.sandbox.risky_downloads"
        private const val WEBVIEW_DATA_SUFFIX = "blokqr_sandbox"
        /**
         * Intent de lancement du bac à sable pour [url].
         * @param riskyDownloads `true` si le verdict n'est pas SAFE/NEUTRAL
         *        (affiche les avertissements de téléchargement/ouverture).
         */
        fun intent(context: Context, url: String, riskyDownloads: Boolean = false): Intent =
            Intent(context, SandboxActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_RISKY_DOWNLOADS, riskyDownloads)
    }
}