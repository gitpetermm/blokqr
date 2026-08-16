package com.blokqr.app.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Ouvre une URL externe (navigateur ou Custom Tab du système) de façon sûre.
 *
 * Utilisé notamment pour la politique de confidentialité EN LIGNE
 * (`Config.PRIVACY_POLICY_URL`). L'écran intégré à l'app (consultable HORS-LIGNE)
 * reste inchangé : ce helper ne fait qu'ouvrir la version web en complément.
 *
 * Ne lève pas d'exception si aucun navigateur n'est disponible : on affiche
 * simplement un Toast.
 */
fun Context.openUrlExternally(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "Aucun navigateur disponible", Toast.LENGTH_SHORT).show()
    }
}