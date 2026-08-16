package com.blokqr.app.ui.tools
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.print.PrintHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
/**
 * Export d'un code généré : partage (feuille système) et impression.
 *
 * Le partage passe par MediaStore (et non plus par un FileProvider) : le PNG est
 * enregistré dans la galerie (Images/BlokQR), ce qui produit une URI
 * content://media/... servie par le FOURNISSEUR SYSTÈME. Toutes les applis
 * (Gmail, WhatsApp, Bluetooth...) peuvent la lire nativement avec le simple
 * FLAG_GRANT_READ_URI_PERMISSION -> fini le « permission denied for attachment »,
 * y compris sur les surcouches OEM qui bloquent les FileProvider tiers.
 * Effet secondaire utile : le code est sauvegardé dans la galerie.
 *
 * minSdk 29 : aucune permission de stockage requise (stockage cloisonné).
 * ROBUSTESSE : aucune fonction ne lève d'exception (renvoie un booléen).
 */
object CodeExport {
    /**
     * Enregistre le code dans la galerie puis ouvre la feuille de partage.
     * @return true si la feuille a été lancée, false sinon.
     */
    suspend fun share(
        context: Context,
        bitmap: Bitmap,
        baseName: String = "blokqr_code",
    ): Boolean = try {
        val uri = withContext(Dispatchers.IO) { saveToGallery(context, bitmap, baseName) }
        if (uri == null) {
            false
        } else {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, null)
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        }
    } catch (e: Exception) {
        false
    }
    /** Impression du code via le framework d'impression Android (protégée). */
    fun print(context: Context, bitmap: Bitmap, jobName: String = "BlokQR"): Boolean = try {
        PrintHelper(context).apply {
            scaleMode = PrintHelper.SCALE_MODE_FIT
            colorMode = PrintHelper.COLOR_MODE_MONOCHROME
        }.printBitmap(jobName, bitmap)
        true
    } catch (e: Exception) {
        false
    }
    /**
     * Écrit le bitmap en PNG dans MediaStore (Images/BlokQR) et renvoie son URI
     * content://media/... (lisible par les autres applis). IS_PENDING garantit
     * que le fichier n'est visible qu'une fois entièrement écrit.
     */
    private fun saveToGallery(context: Context, bitmap: Bitmap, baseName: String): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${baseName}_$stamp.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/BlokQR",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, pending) ?: return null
        val written = resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }
}
