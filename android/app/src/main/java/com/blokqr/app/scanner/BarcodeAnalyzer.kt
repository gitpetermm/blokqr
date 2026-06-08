package com.blokqr.app.scanner

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Analyseur CameraX branché sur ML Kit (décodage on-device, multi-format).
 *
 * Important — sécurité par conception : on se contente d'EXTRAIRE la valeur
 * brute. AUCUNE ouverture, AUCUN appel réseau vers la cible n'est déclenché ici.
 */
class BarcodeAnalyzer(
    private val onResult: (raw: String, symbology: String) -> Unit,
) : ImageAnalysis.Analyzer {

    // ML Kit détecte automatiquement tous les formats courants (QR, Data Matrix,
    // Aztec, PDF417, EAN, Code128, etc.).
    private val scanner = BarcodeScanning.getClient()

    @Volatile
    private var handled = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (handled) { imageProxy.close(); return }
        val media = imageProxy.image ?: run { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(
            media, imageProxy.imageInfo.rotationDegrees
        )
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val first = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                if (first != null && !handled) {
                    handled = true
                    onResult(first.rawValue!!, symbologyName(first.format))
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    fun reset() { handled = false }

    private fun symbologyName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "qr"
        Barcode.FORMAT_DATA_MATRIX -> "data_matrix"
        Barcode.FORMAT_AZTEC -> "aztec"
        Barcode.FORMAT_PDF417 -> "pdf417"
        Barcode.FORMAT_EAN_13 -> "ean13"
        Barcode.FORMAT_EAN_8 -> "ean8"
        Barcode.FORMAT_CODE_128 -> "code128"
        Barcode.FORMAT_CODE_39 -> "code39"
        Barcode.FORMAT_UPC_A -> "upc_a"
        else -> "unknown"
    }
}
