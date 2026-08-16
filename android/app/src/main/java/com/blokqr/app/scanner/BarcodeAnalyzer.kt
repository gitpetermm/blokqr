package com.blokqr.app.scanner
import android.annotation.SuppressLint
import android.graphics.PointF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
/** Un code lu : valeur brute, symbologie, et coins dans le repère IMAGE (redressée). */
data class ScannedCode(
    val raw: String,
    val symbology: String,
    val corners: List<PointF>,
)
/**
 * Résultat d'une frame : les codes détectés + les dimensions de l'image
 * redressée (après rotation), pour mapper les coins vers l'écran.
 */
data class ScanFrame(
    val codes: List<ScannedCode>,
    val sourceWidth: Int,
    val sourceHeight: Int,
)
/**
 * Analyseur CameraX branché sur ML Kit — moteur ÉPROUVÉ (ImageAnalysis classique).
 * TOUS les formats sont détectés (client ML Kit par défaut : QR, Data Matrix,
 * Aztec, PDF417, EAN, Code 128, etc.).
 *
 * Extraction de valeur ROBUSTE : un code détecté n'est jamais perdu à cause de
 * l'encodage. On tente, dans l'ordre : rawValue, puis displayValue, puis les
 * octets bruts décodés en UTF-8 (utile pour les QR en mode octet/binaire dont
 * ML Kit ne renseigne pas rawValue). Seul un code sans AUCUN contenu lisible
 * est ignoré.
 *
 * Sécurité par conception : on EXTRAIT seulement la valeur et la géométrie.
 * AUCUNE ouverture, AUCUN appel réseau vers la cible n'est déclenché ici.
 * Échecs ML Kit jamais silencieux (tag Logcat : BlokQrScan).
 */
class BarcodeAnalyzer(
    private val onResult: (ScanFrame) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient()
    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image ?: run { imageProxy.close(); return }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(media, rotation)
        val swapped = rotation == 90 || rotation == 270
        val srcW = if (swapped) media.height else media.width
        val srcH = if (swapped) media.width else media.height
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val codes = barcodes.mapNotNull { bc ->
                    val value = extractValue(bc)
                    if (value.isNullOrEmpty()) {
                        null
                    } else {
                        ScannedCode(value, symbologyName(bc.format), cornersOf(bc))
                    }
                }
                if (codes.isNotEmpty()) {
                    onResult(ScanFrame(codes, srcW, srcH))
                }
            }
            .addOnFailureListener { e ->
                Log.e("BlokQrScan", "Échec ML Kit sur la frame", e)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
    /**
     * Extraction en cascade de la valeur d'un code :
     * - rawValue (cas courant) ;
     * - sinon displayValue (valeur lisible fournie par ML Kit) ;
     * - sinon octets bruts en UTF-8 si le résultat contient du texte imprimable
     *   (récupère les QR en mode octet dont rawValue est nul).
     */
    private fun extractValue(bc: Barcode): String? {
        bc.rawValue?.let { if (it.isNotEmpty()) return it }
        bc.displayValue?.let { if (it.isNotEmpty()) return it }
        val bytes = bc.rawBytes
        if (bytes != null && bytes.isNotEmpty()) {
            val text = String(bytes, Charsets.UTF_8)
            val printable = text.count { !it.isISOControl() }
            // Au moins la moitié de caractères imprimables : on évite le binaire pur.
            if (text.isNotBlank() && printable * 2 >= text.length) {
                return text
            }
        }
        return null
    }
    private fun cornersOf(bc: Barcode): List<PointF> {
        val pts = bc.cornerPoints
        if (pts != null && pts.size == 4) {
            return pts.map { PointF(it.x.toFloat(), it.y.toFloat()) }
        }
        val box = bc.boundingBox
        if (box != null) {
            return listOf(
                PointF(box.left.toFloat(), box.top.toFloat()),
                PointF(box.right.toFloat(), box.top.toFloat()),
                PointF(box.right.toFloat(), box.bottom.toFloat()),
                PointF(box.left.toFloat(), box.bottom.toFloat()),
            )
        }
        return emptyList()
    }
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
        Barcode.FORMAT_UPC_E -> "upc_e"
        Barcode.FORMAT_ITF -> "itf"
        Barcode.FORMAT_CODABAR -> "codabar"
        Barcode.FORMAT_CODE_93 -> "code93"
        else -> "unknown"
    }
}