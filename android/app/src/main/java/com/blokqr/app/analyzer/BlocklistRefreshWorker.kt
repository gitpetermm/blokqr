package com.blokqr.app.analyzer

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.BackoffPolicy
import com.blokqr.app.Config
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Refresh quotidien de la blocklist locale signée.
 *
 * Workflow :
 *   1. GET https://api.blokqr.com/v1/local-blocklist (sans HMAC : endpoint public).
 *   2. Vérification signature hybride côté BlocklistManager.
 *   3. Si OK, remplace la cached.
 *   4. Si KO, on garde la blocklist active actuelle.
 *
 * Échecs :
 *   - Réseau coupé   : WorkManager retry plus tard (backoff exponentiel).
 *   - HTTP 5xx       : retry plus tard.
 *   - HTTP 503 (no   blocklist générée côté serveur, ex : tout début de
 *     déploiement) : retry plus tard.
 *   - Signature KO   : on ne retry PAS (problème de configuration, pas réseau).
 *
 * Contraintes :
 *   - Réseau connecté (n'importe lequel, pas WiFi obligatoire car ~30 KB).
 *   - Pas de contrainte batterie : la blocklist est utile en mobilité.
 *
 * Planification :
 *   Le travail est ENQUEUE_OR_KEEP : si déjà planifié, on n'en ajoute pas
 *   un deuxième. Réinitialisation par appel explicite de `enqueue(force=true)`.
 */
class BlocklistRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Refresh blocklist demarre...")

        val freshJson = try {
            downloadBlocklist()
        } catch (e: Exception) {
            Log.w(TAG, "Telechargement echoue : ${e.message}")
            return Result.retry()
        }

        // Cas 503 ou réponse vide : retry plus tard.
        if (freshJson == null || freshJson.isEmpty()) {
            Log.w(TAG, "Reponse vide ou 503 (blocklist pas encore generee cote serveur).")
            return Result.retry()
        }

        // Cas réponse JSON trop petite : probablement une page d'erreur.
        if (freshJson.size < 200) {
            Log.w(TAG, "Reponse trop courte (${freshJson.size} octets), ignoree.")
            return Result.retry()
        }

        val ok = BlocklistManager.installFreshBlocklist(applicationContext, freshJson)
        return if (ok) {
            Log.i(TAG, "Refresh blocklist OK.")
            Result.success()
        } else {
            // Signature invalide ou version régressée : pas la peine de retry.
            // Le prochain refresh planifié essaiera de nouveau.
            Log.w(TAG, "Refresh rejete (signature ou version). On essaiera plus tard.")
            Result.success()  // On déclare succès pour éviter retry agressif.
        }
    }

    /**
     * Télécharge le fichier blocklist depuis l'endpoint backend.
     * Renvoie les octets bruts ou null si 503 / pas de body.
     * Lève une exception sur erreur réseau (déclenche retry).
     */
    private fun downloadBlocklist(): ByteArray? {
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("${Config.API_BASE_URL}/v1/local-blocklist")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 503) {
                // Backend OK mais blocklist pas générée -> on retry plus tard.
                return null
            }
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}")
            }
            val body = resp.body ?: return null
            return body.bytes()
        }
    }

    companion object {
        private const val TAG = "BlocklistRefreshWorker"
        const val UNIQUE_WORK_NAME = "blocklist-refresh"
        const val TAG_REFRESH = "blocklist-refresh"

        /**
         * Planifie le refresh périodique. Appelé une fois au démarrage de l'app
         * (BlokQrApp.onCreate). Idempotent : KEEP par défaut.
         *
         * @param replace Si true, force le remplacement (utile pour debug).
         */
        fun enqueue(context: Context, replace: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BlocklistRefreshWorker>(
                repeatInterval = 24, repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 6, flexTimeIntervalUnit = TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,  // 30 minutes de base
                    TimeUnit.MINUTES,
                )
                .addTag(TAG_REFRESH)
                .build()

            val policy = if (replace) {
                ExistingPeriodicWorkPolicy.UPDATE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            }

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, policy, request,
            )
            Log.i(TAG, "Refresh blocklist planifie (24h, policy=$policy).")
        }
    }
}
