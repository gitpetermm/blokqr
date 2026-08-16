package com.blokqr.app.widget
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.blokqr.app.MainActivity
import com.blokqr.app.R
/**
 * Widget d'écran d'accueil : deux actions en un tap — « Scanner » (onglet
 * Scanner) et « Vérifier un lien » (écran d'analyse d'URL, coller un lien reçu).
 * Chaque bouton émet une action de raccourci vers MainActivity (composant
 * explicite : aucune intent-filter supplémentaire nécessaire).
 *
 * RemoteViews (et non Glance) : widget « bouton » simple, aucune dépendance
 * ajoutée, aucun impact R8.
 */
class ScanWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_scan).apply {
                setOnClickPendingIntent(
                    R.id.widget_scan_button,
                    activityIntent(context, MainActivity.ACTION_SHORTCUT_SCAN, REQ_SCAN),
                )
                setOnClickPendingIntent(
                    R.id.widget_verify_button,
                    activityIntent(context, MainActivity.ACTION_SHORTCUT_ANALYZE_URL, REQ_VERIFY),
                )
            }
            appWidgetManager.updateAppWidget(id, views)
        }
    }
    private fun activityIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
    private companion object {
        const val REQ_SCAN = 1
        const val REQ_VERIFY = 2
    }
}
