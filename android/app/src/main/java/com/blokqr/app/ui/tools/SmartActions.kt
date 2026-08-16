package com.blokqr.app.ui.tools
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.ui.graphics.vector.ImageVector
import com.blokqr.app.R
import com.blokqr.app.ui.tools.SmartActionParser.SmartKind
/**
 * Actions intelligentes : selon le contenu décodé, propose un transfert direct
 * vers l'app tierce adaptée (Maps, Téléphone, SMS, E-mail, Contacts, Agenda).
 *
 * Sécurité : ne s'applique QU'aux schémas NON web (geo/tel/sms/mailto/vCard/
 * vEvent). Les liens http(s) — Zoom/Teams inclus — renvoient null ici et restent
 * soumis au verdict d'analyse (voir SmartActionParser.isMeetingLink pour le seul
 * enrichissement de libellé). Tous les intents ouvrent un éditeur/composeur que
 * l'utilisateur confirme : aucune exécution automatique (pas d'appel/envoi seul).
 */
object SmartActions {
    /** Une action proposée : libellé, icône, et lancement protégé (renvoie un booléen). */
    class SmartAction internal constructor(
        // @param: cible explicitement le PARAMÈTRE de constructeur (comportement
        // actuel), ce qui lève l'avertissement Kotlin annonçant que, sans cible,
        // l'annotation s'appliquera aussi au champ dans une version future.
        @param:StringRes val labelRes: Int,
        val icon: ImageVector,
        private val build: (Context) -> Intent,
    ) {
        fun launch(context: Context): Boolean = try {
            val intent = build(context).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
    /** Action de transfert direct applicable au contenu, ou null (rien à proposer). */
    fun forContent(raw: String): SmartAction? {
        val content = raw.trim()
        return when (SmartActionParser.kindOf(content)) {
            SmartKind.MAPS -> SmartAction(R.string.smart_open_maps, Icons.Rounded.Place) {
                Intent(Intent.ACTION_VIEW, Uri.parse(content))
            }
            SmartKind.CALL -> SmartAction(R.string.smart_call, Icons.Rounded.Call) {
                // ACTION_DIAL : ouvre le composeur pré-rempli, n'appelle jamais seul.
                Intent(Intent.ACTION_DIAL, Uri.parse(content))
            }
            SmartKind.SMS -> SmartAction(R.string.smart_sms, Icons.Rounded.Sms) {
                val (number, body) = SmartActionParser.smsParts(content)
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                    if (body != null) putExtra("sms_body", body)
                }
            }
            SmartKind.EMAIL -> SmartAction(R.string.smart_email, Icons.Rounded.Email) {
                Intent(Intent.ACTION_SENDTO, Uri.parse(content))
            }
            SmartKind.CONTACT -> SmartAction(R.string.smart_add_contact, Icons.Rounded.PersonAdd) {
                contactIntent(content)
            }
            SmartKind.EVENT -> SmartAction(R.string.smart_add_event, Icons.Rounded.Event) {
                eventIntent(content)
            }
            null -> null
        }
    }
    /** Insertion d'un contact pré-rempli depuis un vCard (l'utilisateur valide). */
    private fun contactIntent(vcard: String): Intent =
        Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            SmartActionParser.icalField(vcard, "FN")
                ?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            SmartActionParser.icalField(vcard, "TEL")
                ?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            SmartActionParser.icalField(vcard, "EMAIL")
                ?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        }
    /** Insertion d'un évènement pré-rempli depuis un vEvent (l'utilisateur valide). */
    private fun eventIntent(vevent: String): Intent =
        Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            SmartActionParser.icalField(vevent, "SUMMARY")
                ?.let { putExtra(CalendarContract.Events.TITLE, it) }
            SmartActionParser.icalField(vevent, "LOCATION")
                ?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            SmartActionParser.icalField(vevent, "DESCRIPTION")
                ?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            SmartActionParser.parseDtStartMillis(vevent)
                ?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        }
}