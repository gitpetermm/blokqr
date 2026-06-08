package com.blokqr.app.ui.sandbox

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Navigateur ISOLÉ intégré.
 *
 * Le contenu est rendu dans une WebView durcie, hors du contexte de l'OS :
 *   - JavaScript activé mais stockage tiers désactivé, pas d'accès fichiers ;
 *   - aucune persistance (cache/cookies effacés en sortie) ;
 *   - les schémas non http(s) (intent:, tel:, market:…) sont ignorés pour
 *     empêcher tout deep link de quitter le bac à sable.
 *
 * NB : une WebView reste une isolation logicielle locale ; pour un confinement
 * fort (RBI), router cette vue vers un navigateur distant côté serveur.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SandboxBrowserScreen(url: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Fermer")
            }
            Text("Bac à sable — $url", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.databaseEnabled = false
                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: android.webkit.WebResourceRequest?,
                        ): Boolean {
                            val scheme = request?.url?.scheme?.lowercase()
                            // Confine aux schémas web ; bloque deep links / intents.
                            return scheme != "http" && scheme != "https"
                        }
                    }
                    loadUrl(url)
                }
            },
            onRelease = { webView ->
                webView.clearCache(true)
                webView.clearHistory()
                webView.destroy()
            }
        )
    }
}
