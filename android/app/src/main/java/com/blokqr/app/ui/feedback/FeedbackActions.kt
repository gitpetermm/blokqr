package com.blokqr.app.ui.feedback

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.blokqr.app.BuildConfig
import com.blokqr.app.Config
import com.blokqr.app.R
import com.google.android.play.core.review.ReviewManagerFactory
import java.util.Locale

/**
 * Actions de feedback / contact (sans backend, conforme « zéro traceur »).
 *
 * - openPlayStoreListing : ouvre la fiche Play (market:// puis fallback https).
 * - sendFeedbackEmail    : compose un email PRIVÉ vers contact@blokqr.com,
 *   prérempli (sujet + corps + diagnostic technique non sensible).
 * - launchInAppReview    : flux d'avis natif Google Play ; bascule sur la fiche
 *   Play si l'API est indisponible (quota, services Play absents...).
 *
 * Conformité Play : aucune logique de « gating » — la note Play et le contact
 * privé sont tous deux accessibles sans condition depuis l'UI.
 */

fun Context.openPlayStoreListing() {
    try {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(Config.PLAY_STORE_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.feedback_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}

fun Context.sendFeedbackEmail() {
    val subject = getString(R.string.feedback_email_subject)
    val intro = getString(R.string.feedback_email_body)
    val diag = buildString {
        append("\n\n\n---\n")
        append("App: BlokQR ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Locale: ${Locale.getDefault()}\n")
    }
    val uri = Uri.parse(
        "mailto:" + Config.CONTACT_EMAIL +
            "?subject=" + Uri.encode(subject) +
            "&body=" + Uri.encode(intro + diag)
    )
    try {
        startActivity(Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.feedback_no_app, Toast.LENGTH_SHORT).show()
    }
}

fun launchInAppReview(activity: Activity) {
    val manager = ReviewManagerFactory.create(activity)
    manager.requestReviewFlow().addOnCompleteListener { task ->
        if (task.isSuccessful) {
            manager.launchReviewFlow(activity, task.result)
                .addOnFailureListener { activity.openPlayStoreListing() }
        } else {
            // API indisponible : on bascule sur la fiche Play (rien n'est bloqué).
            activity.openPlayStoreListing()
        }
    }
}
