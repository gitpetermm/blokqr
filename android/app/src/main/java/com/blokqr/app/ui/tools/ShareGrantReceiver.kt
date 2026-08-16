package com.blokqr.app.ui.tools
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
/**
 * Reçoit le composant CHOISI par l'utilisateur dans la feuille de partage
 * (EXTRA_CHOSEN_COMPONENT) et accorde EXPLICITEMENT à son package le droit de
 * lecture de l'URI du code partagé. C'est le mécanisme fiable contre
 * « permission denied for attachment » : on ne dépend plus de la propagation
 * automatique du grant ni de la visibilité des applis.
 * exported=false : déclenché uniquement par le système via notre PendingIntent.
 */
class ShareGrantReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val component = IntentCompat.getParcelableExtra(
            intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java,
        )
        val uri = IntentCompat.getParcelableExtra(intent, EXTRA_URI, Uri::class.java)
        if (component != null && uri != null) {
            runCatching {
                context.grantUriPermission(
                    component.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }
    companion object {
        const val EXTRA_URI = "com.blokqr.app.EXTRA_SHARE_URI"
    }
}
