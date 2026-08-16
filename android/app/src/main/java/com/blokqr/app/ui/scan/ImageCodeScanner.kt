package com.blokqr.app.ui.scan
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.scanner.OcrUrlRecognizer
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
/**
 * Choix de la source pour scanner un code/URL depuis une image :
 *  - GALERIE : sélecteur de photos système (PickVisualMedia), décodage de l'URI ;
 *  - PHOTO   : capture pleine résolution DEPUIS LA CAMÉRA EN DIRECT (CameraX
 *              ImageCapture), déléguée à ScannerScreen via onTakePhoto — pas de
 *              FileProvider ni d'application caméra externe.
 *
 * Stratégie « QR d'abord, sinon OCR » (via le helper OcrUrlRecognizer existant).
 *
 * Sécurité : on EXTRAIT seulement une valeur/URL. AUCUNE ouverture, AUCUN appel
 * réseau vers la cible ; l'analyse est confiée au flux existant via onResult.
 *
 * @return une lambda à appeler pour ouvrir le sélecteur de source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberImageCodeScanner(
    onResult: (raw: String, symbology: String) -> Unit,
    onNothingFound: () -> Unit,
    onError: () -> Unit,
    onTakePhoto: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showSheet by remember { mutableStateOf(false) }
    // Décodage d'une image de la GALERIE (URI) : QR d'abord, sinon OCR.
    fun handleGallery(uri: Uri) {
        scope.launch {
            try {
                val hit = decodeImageUri(context, uri)
                if (hit != null) onResult(hit.first, hit.second) else onNothingFound()
            } catch (e: Exception) {
                onError()
            }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) handleGallery(uri)
    }
    // Ferme la feuille (avec animation) puis exécute l'action choisie.
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            showSheet = false
            action()
        }
    }
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.image_source_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                // Ordre : Galerie, puis Prendre une photo.
                SheetItem(
                    icon = Icons.Rounded.Image,
                    label = stringResource(R.string.image_source_gallery),
                    onClick = {
                        closeThen {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    },
                )
                SheetItem(
                    icon = Icons.Rounded.PhotoCamera,
                    label = stringResource(R.string.image_source_camera),
                    onClick = { closeThen { onTakePhoto() } },
                )
            }
        }
    }
    return { showSheet = true }
}
/* --------------------------- UI : élément de feuille --------------------- */
@Composable
private fun SheetItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
/* --------------------------- Décodage : galerie (URI) -------------------- */
/**
 * Décodage d'une image de la galerie (URI) : QR d'abord, sinon repli OCR.
 * Retourne (valeur, symbologie) ou null si rien n'est trouvé. Lève une exception
 * en cas d'échec de lecture (géré par l'appelant -> onError).
 */
private suspend fun decodeImageUri(context: Context, uri: Uri): Pair<String, String>? {
    val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
    val scanner = BarcodeScanning.getClient()
    val barcodes = try {
        scanner.process(image).await()
    } finally {
        scanner.close()
    }
    barcodes.firstNotNullOfOrNull { bc ->
        extractValue(bc)?.let { value -> value to symbologyName(bc.format) }
    }?.let { return it }
    val url = OcrUrlRecognizer.recognizeUrl(context, uri)
    return url?.let { it to "ocr" }
}
/* --------------------- Décodage : cliché caméra (Bitmap) ----------------- */
/**
 * Convertit un ImageProxy (capture ImageCapture) en Bitmap redressé (upright).
 * Réutilisé par ScannerScreen après une capture in-app.
 */
internal fun imageProxyToUprightBitmap(proxy: ImageProxy): Bitmap {
    val bitmap = proxy.toBitmap()
    val degrees = proxy.imageInfo.rotationDegrees
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
/**
 * Décodage d'un cliché caméra (Bitmap) : QR d'abord, sinon repli OCR (variante
 * bitmap du helper existant). Retourne (valeur, symbologie) ou null.
 */
internal suspend fun decodeCapturedBitmap(bitmap: Bitmap): Pair<String, String>? {
    val image = InputImage.fromBitmap(bitmap, 0)
    val scanner = BarcodeScanning.getClient()
    val barcodes = try {
        scanner.process(image).await()
    } finally {
        scanner.close()
    }
    barcodes.firstNotNullOfOrNull { bc ->
        extractValue(bc)?.let { value -> value to symbologyName(bc.format) }
    }?.let { return it }
    val url = OcrUrlRecognizer.recognizeUrl(bitmap)
    return url?.let { it to "ocr" }
}
/* --------------------------- Helpers communs ----------------------------- */
/**
 * Extraction en cascade de la valeur d'un code :
 * - rawValue (cas courant) ;
 * - sinon displayValue ;
 * - sinon octets bruts en UTF-8 si le résultat est majoritairement imprimable.
 */
private fun extractValue(bc: Barcode): String? {
    bc.rawValue?.let { if (it.isNotEmpty()) return it }
    bc.displayValue?.let { if (it.isNotEmpty()) return it }
    val bytes = bc.rawBytes
    if (bytes != null && bytes.isNotEmpty()) {
        val decoded = String(bytes, Charsets.UTF_8)
        val printable = decoded.count { !it.isISOControl() }
        if (decoded.isNotBlank() && printable * 2 >= decoded.length) {
            return decoded
        }
    }
    return null
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