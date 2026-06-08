package com.blokqr.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Classification d'usurpation de marque / hameçonnage EXÉCUTÉE SUR L'APPAREIL.
 *
 * Innovation « double prévisualisation » : le service cloud rend la page finale
 * en bac à sable et renvoie une CAPTURE D'ÉCRAN. C'est cette image que le modèle
 * analyse ici, localement. Le contenu de la page n'est donc jamais transmis à un
 * tiers d'analyse : seul l'appareil juge de l'apparence (imitation de banque, etc.).
 *
 * INTÉGRATION : le modèle `phishing_classifier.tflite` (entraîné via TensorFlow
 * Lite Model Maker sur des captures de pages légitimes vs frauduleuses) doit être
 * placé dans app/src/main/assets/. Tant qu'il est absent, le classifieur se
 * désactive proprement (renvoie null) et l'app s'appuie sur les signaux serveur.
 */
class PhishingClassifier(context: Context) {

    private val interpreter: Interpreter? = runCatching {
        val afd = context.assets.openFd(MODEL_ASSET)
        afd.createInputStream().channel.use { channel ->
            val buf = channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                afd.startOffset, afd.declaredLength
            )
            Interpreter(buf)
        }
    }.getOrNull()

    val available: Boolean get() = interpreter != null

    data class Assessment(val impersonationProbability: Float, val label: String)

    /** Analyse une capture d'écran encodée en base64. Renvoie null si indisponible. */
    fun assess(screenshotB64: String?): Assessment? {
        val itp = interpreter ?: return null
        if (screenshotB64.isNullOrEmpty()) return null

        val bytes = Base64.decode(screenshotB64, Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = Bitmap.createScaledBitmap(bmp, INPUT, INPUT, true)

        val input = ByteBuffer.allocateDirect(4 * INPUT * INPUT * 3)
            .order(ByteOrder.nativeOrder())
        val px = IntArray(INPUT * INPUT)
        scaled.getPixels(px, 0, INPUT, 0, 0, INPUT, INPUT)
        for (p in px) {
            input.putFloat(((p shr 16 and 0xFF) / 255f))
            input.putFloat(((p shr 8 and 0xFF) / 255f))
            input.putFloat(((p and 0xFF) / 255f))
        }

        val output = Array(1) { FloatArray(2) } // [légitime, usurpation]
        itp.run(input, output)
        val prob = output[0][1]
        val label = if (prob >= 0.5f) "usurpation probable" else "apparence légitime"
        return Assessment(prob, label)
    }

    companion object {
        private const val MODEL_ASSET = "phishing_classifier.tflite"
        private const val INPUT = 224
    }
}
