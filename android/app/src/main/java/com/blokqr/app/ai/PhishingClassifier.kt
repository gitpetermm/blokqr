package com.blokqr.app.ai
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.android.gms.tflite.java.TfLite
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
/**
 * Classification d'usurpation de marque / hameçonnage EXÉCUTÉE SUR L'APPAREIL.
 *
 * Innovation « double prévisualisation » : le service cloud rend la page finale
 * en bac à sable et renvoie une CAPTURE D'ÉCRAN. C'est cette image que le modèle
 * analyse ici, localement. Le contenu de la page n'est donc jamais transmis à un
 * tiers d'analyse : seul l'appareil juge de l'apparence (imitation de banque, etc.).
 *
 * RUNTIME : utilise le runtime TensorFlow Lite / LiteRT de GOOGLE PLAY SERVICES
 * (com.google.android.gms:play-services-tflite-java). Le moteur natif est fourni
 * par Play services : AUCUN `.so` TFLite n'est embarqué dans l'app -> conformité
 * 16 Ko garantie pour cette dépendance, et taille d'app réduite + mises à jour
 * automatiques du moteur. L'initialisation est asynchrone (TfLite.initialize).
 *
 * INTÉGRATION : le modèle `phishing_classifier.tflite` (entraîné via TensorFlow
 * Lite Model Maker sur des captures de pages légitimes vs frauduleuses) doit être
 * placé dans app/src/main/assets/. Tant qu'il est absent — ou si le runtime Play
 * services est indisponible — le classifieur se désactive proprement (renvoie
 * null) et l'app s'appuie sur les signaux serveur.
 */
class PhishingClassifier(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var interpreter: InterpreterApi? = null
    @Volatile private var initFailed = false
    private val initMutex = Mutex()
    /** Vrai une fois le runtime + le modèle chargés avec succès. */
    val available: Boolean get() = interpreter != null
    data class Assessment(val impersonationProbability: Float, val label: String)
    /**
     * Prépare le runtime TFLite de Play services puis charge le modèle.
     * Idempotent et thread-safe (double-checked + Mutex). En cas d'échec
     * (runtime absent, modèle manquant/incompatible), bascule en mode désactivé
     * une fois pour toutes (initFailed) : aucune nouvelle tentative coûteuse.
     */
    private suspend fun ensureReady() {
        if (interpreter != null || initFailed) return
        initMutex.withLock {
            if (interpreter != null || initFailed) return
            val itp = runCatching {
                // 1) Runtime fourni par Google Play services (aucun .so embarqué).
                TfLite.initialize(appContext).await()
                // 2) Modèle mappé depuis assets/ ; null si absent.
                val model = loadModel() ?: return@runCatching null
                InterpreterApi.create(
                    model,
                    InterpreterApi.Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY),
                )
            }.getOrNull()
            if (itp == null) initFailed = true else interpreter = itp
        }
    }
    /** Mappe le modèle .tflite en lecture seule. Le MappedByteBuffer reste valide
     *  après fermeture du canal (jusqu'au GC), donc utilisable par l'interpréteur. */
    private fun loadModel(): ByteBuffer? = runCatching {
        appContext.assets.openFd(MODEL_ASSET).use { afd ->
            afd.createInputStream().channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }.getOrNull()
    /** Analyse une capture d'écran encodée en base64. Renvoie null si indisponible. */
    suspend fun assess(screenshotB64: String?): Assessment? {
        if (screenshotB64.isNullOrEmpty()) return null
        ensureReady()
        val itp = interpreter ?: return null
        val bytes = Base64.decode(screenshotB64, Base64.DEFAULT)
        // Décodage SOUS-ÉCHANTILLONNÉ : la capture serveur est bien plus grande
        // que l'entrée du modèle (INPUT x INPUT). On décode donc directement au
        // plus proche de INPUT via inSampleSize (au lieu de charger la pleine
        // résolution pour la réduire ensuite) : moins de mémoire ET inférence
        // plus rapide. On termine par createScaledBitmap pour la taille exacte.
        val bmp = decodeSampled(bytes, INPUT) ?: return null
        val scaled = Bitmap.createScaledBitmap(bmp, INPUT, INPUT, true)
        if (scaled !== bmp) bmp.recycle()
        val input = ByteBuffer.allocateDirect(4 * INPUT * INPUT * 3)
            .order(ByteOrder.nativeOrder())
        val px = IntArray(INPUT * INPUT)
        scaled.getPixels(px, 0, INPUT, 0, 0, INPUT, INPUT)
        for (p in px) {
            input.putFloat((p shr 16 and 0xFF) / 255f)
            input.putFloat((p shr 8 and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        scaled.recycle()
        val output = Array(1) { FloatArray(2) } // [légitime, usurpation]
        itp.run(input, output)
        val prob = output[0][1]
        val label = if (prob >= 0.5f) "usurpation probable" else "apparence légitime"
        return Assessment(prob, label)
    }
    /**
     * Décode une image en la SOUS-ÉCHANTILLONNANT proche d'une dimension cible.
     * Deux passes : (1) lecture des seules dimensions (inJustDecodeBounds), (2)
     * calcul d'inSampleSize (puissance de 2) pour que la plus grande dimension
     * décodée reste >= targetPx, puis décodage réel. Le résultat est ensuite
     * redimensionné exactement par l'appelant. Fournir BitmapFactory.Options
     * lève l'avertissement Play « sous-échantillonnage manquant ».
     */
    private fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
        // Toujours passer un BitmapFactory.Options (meme au repli) : l'analyseur
        // statique de Play signale tout decodeByteArray sans Options, sans
        // raisonner sur la condition. On fournit donc des Options par defaut.
        if (targetPx <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options())
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val src = maxOf(bounds.outWidth, bounds.outHeight)
        if (src <= 0) return null
        var sample = 1
        while (src / (sample * 2) >= targetPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
    companion object {
        private const val MODEL_ASSET = "phishing_classifier.tflite"
        private const val INPUT = 224
    }
}