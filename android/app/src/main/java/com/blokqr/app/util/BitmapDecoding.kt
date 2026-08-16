package com.blokqr.app.util
import android.content.ContentResolver
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
/**
 * Décodage de bitmaps avec sous-échantillonnage (inSampleSize) pour éviter une
 * consommation mémoire excessive. Répond à l'avertissement Play Console
 * « paramètre BitmapFactory.Options manquant » : on lit d'abord les dimensions
 * (inJustDecodeBounds), on calcule un facteur de réduction en puissance de 2,
 * puis on décode à la taille utile seulement.
 *
 * Important : pour une image de GALERIE que l'on veut ANALYSER (lecture d'un QR),
 * garder une cible généreuse (par ex. 2000 px) afin que le code reste décodable.
 * Pour un simple logo affiché, la cible peut être la taille d'affichage réelle.
 */
object BitmapDecoding {

    /** Facteur de réduction (puissance de 2) pour tenir dans reqW x reqH. */
    private fun sampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (srcW <= 0 || srcH <= 0) return 1
        var s = 1
        while ((srcH / (s * 2)) >= reqH && (srcW / (s * 2)) >= reqW) s *= 2
        return s
    }

    /** Décode une ressource drawable, sous-échantillonnée à reqW x reqH (en px). */
    fun decodeResource(res: Resources, resId: Int, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(res, resId, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
        }
        return BitmapFactory.decodeResource(res, resId, opts)
    }

    /** Décode une image choisie dans la galerie (Uri), sous-échantillonnée. */
    fun decodeUri(cr: ContentResolver, uri: Uri, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
        }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
