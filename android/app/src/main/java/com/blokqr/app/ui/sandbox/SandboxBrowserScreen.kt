package com.blokqr.app.ui.sandbox
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.blokqr.app.R
import com.blokqr.app.ui.theme.InfoChip
import com.blokqr.app.ui.theme.Spacing
import java.util.UUID
private const val APK_MIME = "application/vnd.android.package-archive"
private const val DL_CHANNEL_ID = "blokqr_downloads"
/**
 * Navigateur ISOLÉ intégré (durci, process `:sandbox`) avec téléchargement de
 * documents et POLITIQUE D'OUVERTURE FAIL-SAFE.
 *
 * Principe : BlokQR ne doit JAMAIS être le déclencheur de l'ouverture d'un
 * fichier à risque (une fois ouvert dans une app tierce, on ne contrôle plus
 * rien). Donc :
 *   - SAFE / NEUTRAL ([riskyDownloads] = false) : téléchargement + ouverture
 *     proposée (dialog in-app + notification cliquable) ;
 *   - DANGEROUS / UNKNOWN ([riskyDownloads] = true) : téléchargement AUTORISÉ
 *     (avec avertissement fort) mais BlokQR N'OUVRE PAS le fichier — pas de
 *     bouton « Ouvrir », et la notification renvoie seulement vers le dossier
 *     Téléchargements ;
 *   - APK (tout verdict) : jamais d'ouverture depuis BlokQR (ouvrir = installer).
 *
 * Téléchargement toujours précédé d'une confirmation. content:// uniquement
 * (MediaStore pour les blobs, DownloadManager pour http) — jamais file://.
 *
 * minSdk 29 : stockage géré via MediaStore/Downloads (scoped storage), aucune
 * permission WRITE_EXTERNAL_STORAGE ni FileProvider requis.
 *
 * @param riskyDownloads verdict non fiable : avertissements + ouverture désactivée.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SandboxBrowserScreen(
    url: String,
    onClose: () -> Unit,
    riskyDownloads: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var pendingConfirm by remember { mutableStateOf<SandboxDownload?>(null) }
    var blobNonce by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var savedTarget by remember { mutableStateOf<OpenTarget?>(null) }
    var lastDownloadId by remember { mutableLongStateOf(-1L) }
    var pendingHttpName by remember { mutableStateOf("") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val requestDownload: (SandboxDownload) -> Unit = { dl -> pendingConfirm = dl }
    val proceedDownload: (SandboxDownload) -> Unit = { dl ->
        if (dl.isBlob) {
            val nonce = UUID.randomUUID().toString()
            blobNonce = nonce
            webViewRef?.evaluateJavascript(blobFetchJs(dl.url, nonce), null)
        } else {
            pendingHttpName = dl.fileName
            val openable = isOpenable(riskyDownloads, dl.mimeType, dl.fileName)
            lastDownloadId = enqueueSandboxDownload(context, dl, notifyOpen = openable)
        }
    }
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // Fin d'un téléchargement http : propose l'ouverture in-app SI autorisée,
    // sinon informe que le fichier est enregistré mais non ouvert.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == -1L || id != lastDownloadId) return
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
                val uri = dm.getUriForDownloadedFile(id) ?: return
                val mime = dm.getMimeTypeForDownloadedFile(id) ?: "*/*"
                val openable = isOpenable(riskyDownloads, mime, pendingHttpName)
                savedTarget = OpenTarget(uri, mime, pendingHttpName, openable)
                // Si non ouvrable, on remplace la notif système (supprimée à la
                // fin car VISIBILITY_VISIBLE) par une notif -> dossier Téléchargements.
                if (!openable) {
                    postSavedNotification(ctx, uri, mime, pendingHttpName, openFile = false)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    Column(
        Modifier.fillMaxSize().background(cs.background).systemBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().background(cs.surface)
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.sandbox_close),
                    tint = cs.onSurface)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sandbox_title).uppercase(),
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(url, style = MaterialTheme.typography.bodySmall, color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(Spacing.sm))
            InfoChip(text = stringResource(R.string.sandbox_chip),
                color = cs.primary, icon = Icons.Rounded.Shield)
        }
        if (loading) {
            LinearProgressIndicator(
                progress = { (progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = cs.primary, trackColor = cs.surfaceVariant,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(true)
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.setGeolocationEnabled(false)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    addJavascriptInterface(
                        BlobBridge { b64, mime, nonce ->
                            mainHandler.post {
                                if (nonce.isNotEmpty() && nonce == blobNonce) {
                                    blobNonce = null
                                    saveBlobToDownloads(
                                        ctx.applicationContext, b64, mime,
                                        onSaved = { uri, m, name ->
                                            val openable = isOpenable(riskyDownloads, m, name)
                                            savedTarget = OpenTarget(uri, m, name, openable)
                                            postSavedNotification(
                                                ctx.applicationContext, uri, m, name,
                                                openFile = openable)
                                        },
                                        onError = {
                                            Toast.makeText(ctx, R.string.sandbox_download_error,
                                                Toast.LENGTH_LONG).show()
                                        },
                                    )
                                }
                            }
                        },
                        "AndroidBlobBridge",
                    )
                    setDownloadListener { dlUrl, ua, cd, mt, _ ->
                        val scheme = dlUrl.toUri().scheme?.lowercase()
                        if (scheme == "http" || scheme == "https" || scheme == "blob") {
                            requestDownload(buildSandboxDownload(dlUrl, ua, cd, mt))
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                            loading = newProgress < 100
                        }
                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?, callback: GeolocationPermissions.Callback?,
                        ) { callback?.invoke(origin, false, false) }
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean,
                            isUserGesture: Boolean, resultMsg: Message?,
                        ): Boolean {
                            if (view == null || resultMsg == null || !isUserGesture) return false
                            val temp = WebView(view.context)
                            temp.settings.javaScriptEnabled = false
                            temp.settings.allowFileAccess = false
                            temp.settings.allowContentAccess = false
                            temp.setDownloadListener { u, ua, cd, mt, _ ->
                                val sc = u.toUri().scheme?.lowercase()
                                if (sc == "http" || sc == "https" || sc == "blob") {
                                    requestDownload(buildSandboxDownload(u, ua, cd, mt))
                                }
                                temp.post { temp.destroy() }
                            }
                            temp.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    v: WebView?, request: WebResourceRequest?,
                                ): Boolean {
                                    val u = request?.url?.toString()
                                    val sc = request?.url?.scheme?.lowercase()
                                    if (!u.isNullOrBlank() && (sc == "http" || sc == "https")) {
                                        webViewRef?.loadUrl(u)
                                    }
                                    temp.post { temp.destroy() }
                                    return true
                                }
                            }
                            val transport = resultMsg.obj as? WebView.WebViewTransport
                            transport?.webView = temp
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest?,
                        ): Boolean {
                            val scheme = request?.url?.scheme?.lowercase()
                            return scheme != "http" && scheme != "https"
                        }
                        override fun onReceivedSslError(
                            view: WebView?, handler: SslErrorHandler?, error: SslError?,
                        ) { handler?.cancel() }
                    }
                    loadUrl(url)
                }.also { webViewRef = it }
            },
            onRelease = { webView ->
                CookieManager.getInstance().removeAllCookies(null)
                webView.clearCache(true)
                webView.clearHistory()
                webView.destroy()
                webViewRef = null
            },
        )
    }
    // --- Confirmation avant téléchargement (avertissement fort si à risque) ---
    pendingConfirm?.let { dl ->
        val base = stringResource(R.string.sandbox_download_message, dl.fileName)
        val warn = if (riskyDownloads)
            "\n\n" + stringResource(R.string.sandbox_download_risk_warning) else ""
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text(stringResource(R.string.sandbox_download_title)) },
            text = { Text(base + warn) },
            confirmButton = {
                TextButton(onClick = {
                    val d = dl
                    pendingConfirm = null
                    proceedDownload(d)
                }) { Text(stringResource(R.string.sandbox_download_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) {
                    Text(stringResource(R.string.sandbox_download_cancel))
                }
            },
        )
    }
    // --- « Fichier enregistré » : Ouvrir SI autorisé, sinon information ---
    savedTarget?.let { target ->
        val savedMsg = stringResource(R.string.sandbox_download_saved_message, target.fileName)
        if (target.openable) {
            AlertDialog(
                onDismissRequest = { savedTarget = null },
                title = { Text(stringResource(R.string.sandbox_download_saved_title)) },
                text = { Text(savedMsg) },
                confirmButton = {
                    TextButton(onClick = {
                        val t = target
                        savedTarget = null
                        openDownloadedFile(context, t)
                    }) { Text(stringResource(R.string.sandbox_open)) }
                },
                dismissButton = {
                    TextButton(onClick = { savedTarget = null }) {
                        Text(stringResource(R.string.sandbox_dialog_close))
                    }
                },
            )
        } else {
            val isApk = target.mime == APK_MIME || target.fileName.endsWith(".apk", true)
            val reason = if (isApk) stringResource(R.string.sandbox_open_apk_warning)
                         else stringResource(R.string.sandbox_download_risk_warning)
            val notOpened = stringResource(R.string.sandbox_download_not_opened)
            AlertDialog(
                onDismissRequest = { savedTarget = null },
                title = { Text(stringResource(R.string.sandbox_download_saved_title)) },
                text = { Text(savedMsg + "\n\n" + reason + "\n\n" + notOpened) },
                confirmButton = {
                    TextButton(onClick = { savedTarget = null }) {
                        Text(stringResource(R.string.sandbox_dialog_close))
                    }
                },
            )
        }
    }
}
/** Fichier enregistré : Uri content:// + type MIME + nom + ouverture autorisée ? */
private data class OpenTarget(
    val uri: Uri, val mime: String, val fileName: String, val openable: Boolean,
)
/** Données d'un téléchargement en attente de confirmation. */
private data class SandboxDownload(
    val url: String,
    val userAgent: String?,
    val mimeType: String?,
    val fileName: String,
    val isBlob: Boolean,
)
/**
 * Pont JS -> Kotlin pour les téléchargements blob:. Verrouillé par nonce.
 * `receiveBase64` est appelée depuis le JavaScript de la page (via
 * @JavascriptInterface) : elle n'a aucun appelant Kotlin, d'où l'annotation.
 */
@Keep
private class BlobBridge(private val onBlob: (String, String, String) -> Unit) {
    @Keep
    @JavascriptInterface
    @Suppress("unused")
    fun receiveBase64(base64: String, mimeType: String, nonce: String) {
        onBlob(base64, mimeType, nonce)
    }
}
/** BlokQR n'ouvre un fichier QUE s'il n'est pas à risque ET pas un APK. */
private fun isOpenable(risky: Boolean, mime: String?, fileName: String): Boolean =
    !risky && mime != APK_MIME && !fileName.endsWith(".apk", ignoreCase = true)
private fun buildSandboxDownload(
    url: String, userAgent: String?, contentDisposition: String?, mimeType: String?,
): SandboxDownload {
    val isBlob = url.toUri().scheme?.lowercase() == "blob"
    val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
    return SandboxDownload(url, userAgent, mimeType, name, isBlob)
}
private fun blobFetchJs(blobUrl: String, nonce: String): String = """
    (function() {
      try {
        var x = new XMLHttpRequest();
        x.open('GET', '$blobUrl', true);
        x.responseType = 'blob';
        x.onload = function() {
          if (this.status === 200) {
            var blob = this.response;
            var r = new FileReader();
            r.onloadend = function() {
              var res = '' + r.result;
              var b64 = res.substring(res.indexOf(',') + 1);
              AndroidBlobBridge.receiveBase64(
                b64, blob.type || 'application/octet-stream', '$nonce');
            };
            r.readAsDataURL(blob);
          }
        };
        x.send();
      } catch (e) {}
    })();
""".trimIndent()
/**
 * Télécharge une URL http(s) via DownloadManager. [notifyOpen] contrôle la
 * notification système : NOTIFY_COMPLETED (ouvre au clic) uniquement si
 * l'ouverture est autorisée ; sinon VISIBLE (aucune notif d'ouverture à la fin).
 * Renvoie l'id (ou -1).
 */
private fun enqueueSandboxDownload(context: Context, dl: SandboxDownload, notifyOpen: Boolean): Long {
    return try {
        val request = DownloadManager.Request(dl.url.toUri()).apply {
            dl.mimeType?.let { setMimeType(it) }
            if (!dl.userAgent.isNullOrBlank()) addRequestHeader("User-Agent", dl.userAgent)
            CookieManager.getInstance().getCookie(dl.url)?.let { cookie ->
                if (cookie.isNotBlank()) addRequestHeader("Cookie", cookie)
            }
            setTitle(dl.fileName)
            setDescription(context.getString(R.string.sandbox_chip))
            setNotificationVisibility(
                if (notifyOpen) DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                else DownloadManager.Request.VISIBILITY_VISIBLE
            )
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, dl.fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        Toast.makeText(context,
            context.getString(R.string.sandbox_download_started, dl.fileName),
            Toast.LENGTH_SHORT).show()
        dm?.enqueue(request) ?: -1L
    } catch (_: Exception) {
        Toast.makeText(context, R.string.sandbox_download_error, Toast.LENGTH_LONG).show()
        -1L
    }
}
/** Enregistre un blob (base64) dans « Téléchargements » (MediaStore) et renvoie une content:// Uri. */
private fun saveBlobToDownloads(
    context: Context, base64: String, mimeType: String,
    onSaved: (Uri, String, String) -> Unit, onError: () -> Unit,
) {
    Thread {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
            val fileName = "blokqr_${System.currentTimeMillis()}.$ext"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Handler(Looper.getMainLooper()).post { onSaved(uri, mimeType, fileName) }
        } catch (_: Exception) {
            Handler(Looper.getMainLooper()).post { onError() }
        }
    }.start()
}
/** Ouvre un fichier téléchargé (content:// Uri) via ACTION_VIEW. */
private fun openDownloadedFile(context: Context, target: OpenTarget) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target.uri, target.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, R.string.sandbox_open_no_app, Toast.LENGTH_LONG).show()
    }
}
/**
 * Notification « fichier enregistré ». Si [openFile] : clic -> ouvre le fichier.
 * Sinon (fichier à risque / APK) : clic -> ouvre seulement le dossier
 * Téléchargements (BlokQR n'ouvre jamais un fichier à risque).
 */
private fun postSavedNotification(
    context: Context, uri: Uri, mime: String, fileName: String, openFile: Boolean,
) {
    try {
        // Android 13+ : ne pas notifier sans POST_NOTIFICATIONS accordée. Le
        // dialog in-app (« Ouvrir » / « enregistré ») reste le canal principal ;
        // la notification est un complément, omis proprement si non autorisée.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                DL_CHANNEL_ID,
                context.getString(R.string.sandbox_download_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val tapIntent = if (openFile) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, fileName.hashCode(), tapIntent, flags)
        val notif = NotificationCompat.Builder(context, DL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.sandbox_download_saved_title))
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(context).notify(fileName.hashCode(), notif)
    } catch (_: Exception) {
        // POST_NOTIFICATIONS non accordée (Android 13+) : le dialog in-app suffit.
    }
}
