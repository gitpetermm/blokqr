package com.blokqr.app.ui.result
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import com.blokqr.app.model.Verdict
import java.io.File
import java.io.FileOutputStream
/**
 * Génère une CARTE DE VERDICT partageable (image PNG), dessinée au Canvas natif
 * (aucune dépendance, R8-safe). Format 1080 x 1350 (ratio 4:5, adapté aux
 * réseaux / stories). L'image contient : marque BlokQR, pastille de verdict
 * colorée, libellé + phrase, URL analysée (tronquée), nombre de redirections,
 * et une signature « Vérifié par BlokQR — blokqr.com ».
 *
 * Aucune donnée ne quitte l'appareil autrement que par le partage explicitement
 * déclenché par l'utilisateur. Le fichier est écrit dans cacheDir/shared/ et
 * exposé en content:// via le FileProvider `${applicationId}.downloads.fileprovider`.
 *
 * Tous les textes affichés sont fournis par l'appelant (déjà localisés) pour
 * éviter toute dépendance à un Context de ressources dans le moteur de rendu.
 */
object VerdictCardRenderer {
    /** Données textuelles (déjà localisées) à peindre sur la carte. */
    data class CardText(
        val verdictLabel: String,   // ex. "Danger"
        val headline: String,       // phrase courte sous le libellé
        val url: String,            // URL/valeur analysée
        val redirectsLine: String?, // ex. "3 redirections" (ou null)
        val footer: String,         // ex. "Vérifié par BlokQR — blokqr.com"
        val brand: String,          // "BlokQR"
    )
    private const val W = 1080
    private const val H = 1350
    /**
     * Rend la carte et renvoie un content:// prêt pour ACTION_SEND image/png,
     * ou null en cas d'échec d'écriture.
     */
    fun render(context: Context, verdict: Verdict, text: CardText): Uri? {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val accent = accentColor(verdict)

        // Fond sombre de marque.
        canvas.drawColor(Color.parseColor("#0B1220"))

        // Liseré d'accent en haut.
        Paint().apply {
            color = accent
            style = Paint.Style.FILL
        }.also { canvas.drawRect(0f, 0f, W.toFloat(), 12f, it) }

        val pTitle = textPaint(Color.parseColor("#9DB6F5"), 44f, bold = true)
        val pVerdict = textPaint(accent, 96f, bold = true)
        val pHead = textPaint(Color.WHITE, 44f, bold = false)
        val pUrlLabel = textPaint(Color.parseColor("#5B6472"), 34f, bold = true)
        val pUrl = textPaint(Color.parseColor("#E3E9F2"), 40f, bold = false)
        val pMeta = textPaint(Color.parseColor("#9DB6F5"), 36f, bold = false)
        val pFooter = textPaint(Color.parseColor("#5B6472"), 34f, bold = false)

        // Marque.
        canvas.drawText(text.brand.uppercase(), 80f, 150f, pTitle)

        // Pastille de verdict (cercle plein).
        val cx = W / 2f
        val cy = 460f
        val radius = 150f
        Paint().apply {
            isAntiAlias = true
            color = accent
            alpha = 40
            style = Paint.Style.FILL
        }.also { canvas.drawCircle(cx, cy, radius + 40f, it) }
        Paint().apply {
            isAntiAlias = true
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 14f
        }.also { canvas.drawCircle(cx, cy, radius, it) }
        // Glyphe simple au centre (coche / point d'exclamation / croix) selon sévérité.
        drawVerdictGlyph(canvas, verdict, cx, cy, radius, accent)

        // Libellé de verdict (centré).
        drawCentered(canvas, text.verdictLabel, cx, 760f, pVerdict)
        // Phrase courte (centrée, éventuellement sur 2 lignes).
        drawCenteredWrapped(canvas, text.headline, cx, 840f, pHead, maxWidth = W - 160f, lineHeight = 58f)

        // Carte URL.
        val boxTop = 980f
        val boxRect = RectF(80f, boxTop, W - 80f, boxTop + 230f)
        Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#111C33")
            style = Paint.Style.FILL
        }.also { canvas.drawRoundRect(boxRect, 28f, 28f, it) }
        canvas.drawText("URL", 120f, boxTop + 60f, pUrlLabel)
        // URL tronquée sur 2 lignes max.
        val urlLines = wrapEllipsized(text.url, pUrl, maxWidth = W - 240f, maxLines = 2)
        var uy = boxTop + 118f
        for (line in urlLines) {
            canvas.drawText(line, 120f, uy, pUrl)
            uy += 52f
        }
        text.redirectsLine?.let { canvas.drawText(it, 120f, boxTop + 200f, pMeta) }

        // Pied de page (signature).
        drawCentered(canvas, text.footer, cx, H - 90f, pFooter)

        // Écriture PNG dans cacheDir/shared/.
        return runCatching {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val out = File(dir, "blokqr_verdict.png")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(
                context,
                context.packageName + ".downloads.fileprovider",
                out,
            )
        }.getOrNull().also { bmp.recycle() }
    }

    private fun accentColor(v: Verdict): Int = when (v) {
        Verdict.SAFE, Verdict.NEUTRAL -> Color.parseColor("#1F9D6B")
        Verdict.UNKNOWN, Verdict.DANGEROUS -> Color.parseColor("#E8A33D")
        Verdict.MALICIOUS -> Color.parseColor("#E0483B")
    }

    private fun textPaint(c: Int, size: Float, bold: Boolean) = Paint().apply {
        isAntiAlias = true
        color = c
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun drawCentered(canvas: Canvas, s: String, cx: Float, y: Float, p: Paint) {
        val w = p.measureText(s)
        canvas.drawText(s, cx - w / 2f, y, p)
    }

    private fun drawCenteredWrapped(
        canvas: Canvas, s: String, cx: Float, top: Float, p: Paint,
        maxWidth: Float, lineHeight: Float,
    ) {
        val lines = wrapEllipsized(s, p, maxWidth, maxLines = 2)
        var y = top
        for (line in lines) {
            drawCentered(canvas, line, cx, y, p)
            y += lineHeight
        }
    }

    /** Découpe un texte en lignes bornées en largeur, la dernière ellipsée. */
    private fun wrapEllipsized(s: String, p: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (p.measureText(s) <= maxWidth) return listOf(s)
        val words = s.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        // Cas des chaînes sans espaces (URLs) : découpe caractère par caractère.
        val tokens = if (words.size <= 1) s.map { it.toString() } else words
        val sep = if (words.size <= 1) "" else " "
        for (tok in tokens) {
            val candidate = if (current.isEmpty()) tok else current.toString() + sep + tok
            if (p.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                lines.add(current.toString())
                current = StringBuilder(tok)
                if (lines.size == maxLines) break
            }
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines.add(current.toString())
        // Ellipse sur la dernière ligne si on a débordé.
        if (lines.size == maxLines && p.measureText(s) > maxWidth) {
            var last = lines[maxLines - 1]
            while (last.isNotEmpty() && p.measureText("$last…") > maxWidth) {
                last = last.dropLast(1)
            }
            lines[maxLines - 1] = "$last…"
        }
        return lines.take(maxLines)
    }

    private fun drawVerdictGlyph(canvas: Canvas, v: Verdict, cx: Float, cy: Float, r: Float, accent: Int) {
        val p = Paint().apply {
            isAntiAlias = true
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 18f
            strokeCap = Paint.Cap.ROUND
        }
        when (v) {
            Verdict.SAFE, Verdict.NEUTRAL -> {
                // Coche.
                val path = android.graphics.Path().apply {
                    moveTo(cx - 60f, cy + 4f)
                    lineTo(cx - 14f, cy + 50f)
                    lineTo(cx + 66f, cy - 46f)
                }
                canvas.drawPath(path, p)
            }
            Verdict.MALICIOUS -> {
                // Croix.
                canvas.drawLine(cx - 48f, cy - 48f, cx + 48f, cy + 48f, p)
                canvas.drawLine(cx + 48f, cy - 48f, cx - 48f, cy + 48f, p)
            }
            else -> {
                // Point d'exclamation (prudence / inconnu).
                canvas.drawLine(cx, cy - 58f, cx, cy + 24f, p)
                val dot = Paint().apply { isAntiAlias = true; color = accent; style = Paint.Style.FILL }
                canvas.drawCircle(cx, cy + 58f, 12f, dot)
            }
        }
    }
}
