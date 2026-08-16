package com.blokqr.app.widget
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.blokqr.app.MainActivity
/**
 * Tuile « Réglages rapides » (volet déroulant) : un tap ouvre BlokQR sur
 * l'onglet Scanner, caméra active. Réutilise l'action de raccourci existante
 * ACTION_SHORTCUT_SCAN (aucune logique de navigation nouvelle).
 *
 * exported=true + permission BIND_QUICK_SETTINGS_TILE (déclarées au manifeste) :
 * requis par Android pour qu'il puisse lier la tuile.
 */
class ScanTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SHORTCUT_SCAN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // API 34+ : startActivityAndCollapse(Intent) est supprimé, il faut un
        // PendingIntent. En dessous, on garde la surcharge Intent.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
