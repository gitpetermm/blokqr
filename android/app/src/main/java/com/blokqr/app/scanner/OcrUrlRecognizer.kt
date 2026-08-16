package com.blokqr.app.scanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reconnaissance de texte 100 % ON-DEVICE (ML Kit, modèle latin embarqué).
 *
 * Aucune image ni texte ne quitte l'appareil — cohérent avec la conception
 * privacy-first de BlokQR. Sert à lire une URL imprimée/affichée (photo ou
 * image de la galerie), puis à la proposer ÉDITABLE dans le pipeline d'analyse.
 */
object OcrUrlRecognizer {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** OCR d'un bitmap (photo caméra). Renvoie le texte reconnu (peut être vide). */
    suspend fun recognizeText(bitmap: Bitmap): String =
        process(InputImage.fromBitmap(bitmap, 0))

    /** OCR d'une image de la galerie (URI). */
    suspend fun recognizeText(context: Context, uri: Uri): String =
        process(InputImage.fromFilePath(context, uri))

    /** OCR + extraction de la meilleure URL candidate (ou null). */
    suspend fun recognizeUrl(bitmap: Bitmap): String? =
        UrlTextExtractor.extract(recognizeText(bitmap))

    /** OCR + extraction depuis une image de la galerie. */
    suspend fun recognizeUrl(context: Context, uri: Uri): String? =
        UrlTextExtractor.extract(recognizeText(context, uri))

    private suspend fun process(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
}