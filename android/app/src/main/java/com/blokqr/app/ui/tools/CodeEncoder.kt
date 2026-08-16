package com.blokqr.app.ui.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.pow

/**
 * Formats de génération pris en charge (encodés 100 % HORS-LIGNE via ZXing).
 * Ce sont EXACTEMENT les symbologies que le scanner (ML Kit) sait relire : la
 * boucle génération -> lecture est donc complète et symétrique.
 * matrix = true -> code 2D carré ; false -> code 1D / empilé (rendu en bande).
 */
enum class CodeFormat(internal val zxing: BarcodeFormat, val matrix: Boolean) {
    // --- 2D ---
    QR(BarcodeFormat.QR_CODE, true),
    DATA_MATRIX(BarcodeFormat.DATA_MATRIX, true),
    AZTEC(BarcodeFormat.AZTEC, true),
    PDF417(BarcodeFormat.PDF_417, false),
    // --- 1D usage général ---
    CODE_128(BarcodeFormat.CODE_128, false),
    CODE_39(BarcodeFormat.CODE_39, false),
    CODE_93(BarcodeFormat.CODE_93, false),
    CODABAR(BarcodeFormat.CODABAR, false),
    ITF(BarcodeFormat.ITF, false),
    // --- 1D commerce (GTIN) ---
    EAN_13(BarcodeFormat.EAN_13, false),
    EAN_8(BarcodeFormat.EAN_8, false),
    UPC_A(BarcodeFormat.UPC_A, false),
    UPC_E(BarcodeFormat.UPC_E, false),
}

/**
 * Encodeur de codes 100 % local. Aucune donnée ne quitte l'appareil : le code
 * est calculé sur le téléphone, cohérent avec la promesse « no-network ».
 *
 * Personnalisation couleur (modules + fond) :
 *  - Autorisée UNIQUEMENT sur les codes 2D (QR, Data Matrix, Aztec). Les codes
 *    1D (EAN, Code 128...) restent NOIR sur BLANC : les lecteurs de codes-barres
 *    linéaires exigent des barres sombres sur fond clair.
 *  - Encadrée par un garde-fou de lisibilité (contraste WCAG + modules plus
 *    sombres que le fond) exposé via [isScanSafe] / [contrastRatio], que l'UI
 *    utilise pour prévenir l'utilisateur et bloquer l'export d'un code illisible.
 *  - Quand des couleurs sont utilisées, la correction d'erreur est relevée à
 *    au moins Q pour absorber toute perte de contraste résiduelle.
 *  - La marge « quiet zone » autour du code est toujours conservée.
 *  - Logo central optionnel (QR uniquement) : force la correction d'erreur H et
 *    reste limité à ~20 % du côté pour ne jamais casser la relecture.
 */
object CodeEncoder {
    private const val BLACK = Color.BLACK
    private const val WHITE = Color.WHITE

    /**
     * Contraste minimal (ratio de luminance WCAG, de 1.0 à 21.0) exigé pour
     * qu'un QR coloré reste fiable au scan. En dessous, l'UI refuse l'export.
     * 3.0 est un plancher prudent ; plus le ratio est élevé, mieux c'est.
     */
    const val MIN_SCAN_CONTRAST = 3.0

    /**
     * Taille du logo central, en fraction du côté du QR (0.20 = 20 %). Au-delà
     * de ~25 % le logo dépasse la capacité de correction H et le code devient
     * illisible (vérifié par décodage). La correction est forcée à H dès qu'un
     * logo est présent.
     */
    private const val LOGO_RATIO = 0.20f

    /**
     * Encode [content] au format [format]. [sizePx] = côté (2D) ou largeur (1D).
     * [fgColor]/[bgColor] : couleurs des modules et du fond (ARGB). Appliquées
     * seulement aux formats 2D ; ignorées (forcées noir/blanc) pour les 1D.
     * Lève une exception si le contenu est invalide pour le format (ex. EAN mal
     * formé, lettres en ITF…) -> utiliser [encodeOrNull] côté UI.
     */
    fun encode(
        content: String,
        format: CodeFormat,
        sizePx: Int,
        marginModules: Int = 1,
        ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
        fgColor: Int = BLACK,
        bgColor: Int = WHITE,
        logo: Bitmap? = null,
    ): Bitmap {
        require(content.isNotEmpty()) { "Contenu vide" }

        // La couleur ne s'applique qu'aux codes 2D. Pour un 1D, on force le
        // noir/blanc quoi que demande l'appelant.
        val colored = format.matrix && (fgColor != BLACK || bgColor != WHITE)
        val fg = if (format.matrix) fgColor else BLACK
        val bg = if (format.matrix) bgColor else WHITE

        // Logo central : QR uniquement (incrustation trop risquée sur Data
        // Matrix / Aztec, impossible sur 1D).
        val useLogo = logo != null && format == CodeFormat.QR

        // Correction d'erreur : H si logo (récupère ~30 %, indispensable pour
        // masquer le centre), sinon >= Q dès qu'on colore, sinon la valeur donnée.
        val effEcc = when {
            useLogo -> ErrorCorrectionLevel.H
            colored && ecc.ordinal < ErrorCorrectionLevel.Q.ordinal -> ErrorCorrectionLevel.Q
            else -> ecc
        }

        val width = sizePx
        val height = if (format.matrix) sizePx else (sizePx / 3).coerceAtLeast(120)
        val hints = buildMap<EncodeHintType, Any> {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.MARGIN, marginModules) // quiet zone
            if (format == CodeFormat.QR) {
                put(EncodeHintType.ERROR_CORRECTION, effEcc)
            }
        }
        val matrix = MultiFormatWriter().encode(content, format.zxing, width, height, hints)
        val base = matrix.toBitmap(fg, bg)
        return if (useLogo) base.withCenterLogo(logo!!, bg) else base
    }

    /** Variante sûre pour l'UI : renvoie null si le contenu est invalide. */
    fun encodeOrNull(
        content: String,
        format: CodeFormat,
        sizePx: Int,
        marginModules: Int = 1,
        ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
        fgColor: Int = BLACK,
        bgColor: Int = WHITE,
        logo: Bitmap? = null,
    ): Bitmap? = try {
        encode(content, format, sizePx, marginModules, ecc, fgColor, bgColor, logo)
    } catch (e: Exception) {
        null
    }

    /* ------------------------- Lisibilité / contraste --------------------- */

    /** Luminance relative WCAG d'une couleur ARGB (0.0 = noir, 1.0 = blanc). */
    fun relativeLuminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = channel(Color.red(color))
        val g = channel(Color.green(color))
        val b = channel(Color.blue(color))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Ratio de contraste WCAG entre deux couleurs (1.0 .. 21.0). */
    fun contrastRatio(c1: Int, c2: Int): Double {
        val l1 = relativeLuminance(c1)
        val l2 = relativeLuminance(c2)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * Vrai si la paire (modules, fond) est fiable au scan : contraste suffisant
     * ET modules plus SOMBRES que le fond (les lecteurs QR attendent des modules
     * foncés sur fond clair).
     */
    fun isScanSafe(fgColor: Int, bgColor: Int): Boolean =
        contrastRatio(fgColor, bgColor) >= MIN_SCAN_CONTRAST &&
            relativeLuminance(fgColor) < relativeLuminance(bgColor)

    private fun BitMatrix.toBitmap(fg: Int, bg: Int): Bitmap {
        val w = width
        val h = height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (this[x, y]) fg else bg
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /**
     * Incruste [logo] au centre du QR, sur une pastille arrondie de la couleur
     * de fond (petite marge) pour le détacher des modules. Taille = [LOGO_RATIO]
     * du côté. Le QR doit avoir été encodé en correction H. Le bitmap récepteur
     * est mutable (issu de createBitmap) : on dessine dessus et on le renvoie.
     */
    private fun Bitmap.withCenterLogo(logo: Bitmap, bg: Int): Bitmap {
        val canvas = Canvas(this)
        val side = width * LOGO_RATIO
        val cx = width / 2f
        val cy = height / 2f
        val pad = side * 0.16f
        val half = side / 2f + pad
        val plate = RectF(cx - half, cy - half, cx + half, cy + half)
        val platePaint = Paint().apply {
            isAntiAlias = true
            color = bg
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(plate, half * 0.28f, half * 0.28f, platePaint)
        val dst = RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        val logoPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        canvas.drawBitmap(logo, null, dst, logoPaint)
        return this
    }
}
