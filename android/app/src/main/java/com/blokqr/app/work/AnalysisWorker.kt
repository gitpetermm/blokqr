package com.blokqr.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blokqr.app.BlokQrApp
import com.blokqr.app.R
import com.blokqr.app.data.LocaleHelper
import com.blokqr.app.data.ScanHistoryStore
import com.blokqr.app.data.SettingsStore

/**
 * Analyse exécutée en arrière-plan : l'évaluation se poursuit même si
 * l'utilisateur quitte l'application. Une notification « Analyse en cours… »
 * est affichée puis mise à jour avec le verdict final.
 *
 * Pour les analyses interactives (premier plan), le ViewModel appelle
 * directement BlokQrApi ; ce worker couvre les scans différés / repris.
 *
 * Note Paquet 3A : on utilise le SINGLETON applicatif BlokQrApp.api au lieu
 * d'instancier un nouveau BlokQrApi(). C'est obligatoire désormais car le
 * constructeur exige InstallTokenManager (signature HMAC par installation),
 * et un Worker isolé ne saurait pas le provisionner. Côté pratique, c'est
 * aussi correct : le manifeste de clés et l'install_id sont mutualisés
 * entre tous les chemins d'appel (UI et arrière-plan).
 */
class AnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    /**
     * Contexte enveloppé dans la langue choisie par l'utilisateur, relue à
     * l'exécution. Garantit que les notifications d'arrière-plan respectent la
     * langue de l'app, indépendamment de la langue du système et du moment où
     * le processus a démarré.
     */
    private val localized: Context by lazy {
        LocaleHelper.wrap(applicationContext, SettingsStore(applicationContext).languageTag())
    }

    /**
     * Singleton applicatif de l'API BlokQR. Ne JAMAIS faire `BlokQrApi(...)`
     * ici : le constructeur exige un InstallTokenManager (Paquet 3A) et le
     * singleton de BlokQrApp est le seul endroit où il est correctement
     * provisionné. Conserver aussi le bénéfice du cache du manifeste partagé.
     */
    private val api by lazy { (applicationContext as BlokQrApp).api }

    override suspend fun doWork(): Result {
        val raw = inputData.getString(KEY_RAW) ?: return Result.failure()
        val symbology = inputData.getString(KEY_SYMBOLOGY)
        notify(
            localized.getString(R.string.notif_analyzing_title),
            localized.getString(R.string.notif_analyzing_text),
        )
        return try {
            val history = ScanHistoryStore(applicationContext)
            val prior = history.priorDestinationHash(raw)
            val result = api.analyze(raw, symbology, prior)
            history.remember(raw, result.report.currentDestinationHash)
            notify(
                localized.getString(R.string.notif_done_title),
                "${result.verdict.label} — ${result.report.displayedValue}",
            )
            Result.success()
        } catch (e: Exception) {
            notify(
                localized.getString(R.string.notif_interrupted_title),
                e.message ?: localized.getString(R.string.notif_network_error),
            )
            Result.retry()
        }
    }

    private fun notify(title: String, text: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        // Canal requis depuis Android 8 ; minSdk=29 -> toujours présent.
        // Re-création avec le nom localisé : met à jour le libellé du canal.
        nm.createNotificationChannel(
            NotificationChannel(
                BlokQrApp.CHANNEL_ID,
                localized.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val notif = NotificationCompat.Builder(localized, BlokQrApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(false)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    companion object {
        const val KEY_RAW = "raw_payload"
        const val KEY_SYMBOLOGY = "symbology"
        private const val NOTIF_ID = 4201
    }
}