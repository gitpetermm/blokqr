package com.blokqr.app.ui.result

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.blokqr.app.BuildConfig
import com.blokqr.app.Config
import com.blokqr.app.R
import java.util.Locale

/**
 * Copier dans le presse-papier, partager un résumé, et signaler un lien
 * malveillant — le tout sans backend (cohérent avec « zéro traceur »).
 *
 * Sur Android 13+, le système affiche sa propre confirmation de copie : on
 * n'ajoute le toast que sur les versions antérieures pour éviter un double feedback.
 */
fun Context.copyToClipboard(label: String, text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, R.string.result_copied, Toast.LENGTH_SHORT).show()
    }
}

fun Context.shareVerdict(summary: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, summary)
    }
    runCatching {
        startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Signale un lien malveillant : ouvre un email PRÉ-REMPLI vers l'équipe BlokQR
 * (Config.CONTACT_EMAIL) avec l'URL, le verdict et les codes de signaux. Permet
 * d'alimenter la curation de la blocklist signée sans backend communautaire.
 * Les codes de signaux (bruts, ex. "google_web_risk") sont volontairement inclus :
 * ils sont précieux pour le triage interne.
 */
fun Context.sendReportEmail(url: String, verdict: String, signalCodes: List<String>) {
    val subject = getString(R.string.report_email_subject)
    val intro = getString(R.string.report_email_intro)
    val body = buildString {
        append(intro).append("\n\n")
        append("URL: ").append(url).append("\n")
        append("Verdict: ").append(verdict).append("\n")
        if (signalCodes.isNotEmpty()) {
            append("Signals: ").append(signalCodes.joinToString(", ")).append("\n")
        }
        append("\n---\n")
        append("App: BlokQR ").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        append("Locale: ").append(Locale.getDefault()).append("\n")
    }
    val uri = Uri.parse(
        "mailto:" + Config.CONTACT_EMAIL +
            "?subject=" + Uri.encode(subject) +
            "&body=" + Uri.encode(body)
    )
    try {
        startActivity(Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.feedback_no_app, Toast.LENGTH_SHORT).show()
    }
}
