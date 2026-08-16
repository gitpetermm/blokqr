package com.blokqr.app.ui.result
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
/**
 * Partage une image (carte de verdict) via le sélecteur système, en image/png.
 * L'Uri doit provenir du FileProvider (content://). On accorde l'accès en
 * lecture temporaire aux applications réceptrices.
 */
fun Context.shareVerdictImage(uri: Uri, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri(null, uri)
    }
    val chooser = Intent.createChooser(send, chooserTitle).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ContextCompat.startActivity(this, chooser, null)
}
