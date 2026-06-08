package com.blokqr.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blokqr.app.R
import com.blokqr.app.data.GeoProvider
import com.blokqr.app.data.ScanHistoryStore
import com.blokqr.app.net.BlokQrApi

/**
 * Analyse exécutée en arrière-plan : l'évaluation se poursuit même si
 * l'utilisateur quitte l'application. Une notification « Analyse en cours… »
 * est affichée puis mise à jour avec le verdict final.
 *
 * Pour les analyses interactives (premier plan), le ViewModel appelle
 * directement BlokQrApi ; ce worker couvre les scans déférés / repris.
 */
class AnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val raw = inputData.getString(KEY_RAW) ?: return Result.failure()
        val symbology = inputData.getString(KEY_SYMBOLOGY)
        notify("Analyse en cours…", "Vérification de la destination du QR code.")

        return try {
            val history = ScanHistoryStore(applicationContext)
            val prior = history.priorDestinationHash(raw)
            val geo = GeoProvider(applicationContext).coarseGeohash()

            val result = BlokQrApi().analyze(raw, symbology, prior, geo)
            history.remember(raw, result.report.currentDestinationHash)

            notify("Analyse terminée", "${result.verdict.label} — ${result.report.displayedValue}")
            Result.success()
        } catch (e: Exception) {
            notify("Analyse interrompue", e.message ?: "Erreur réseau.")
            Result.retry()
        }
    }

    private fun notify(title: String, text: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Analyses BlokQR",
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL)
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
        private const val CHANNEL = "qr_analysis"
        private const val NOTIF_ID = 4201
    }
}
