package com.blokqr.app.ui.splash
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Écran de marque animé (« brand reveal ») joué au démarrage, APRÈS le splash
 * système et AVANT l'app. Conforme aux recommandations Google : court (~1,7 s),
 * SKIPPABLE (tap), et respectant le réglage système « réduire les animations »
 * (durée d'animation = 0 -> on passe directement). Aucune logique métier ; pur
 * habillage. Centré sur le symbole réel BlokQR : QR + cadre de visée, laser vert
 * qui balaie, anneau de protection lumineux qui pulse, puis le logotype.
 *
 * Durées/intensités regroupées en tête pour un réglage facile après test.
 */
private const val DURATION_MS = 1700

// Palette charte (locale, pour être indépendant du thème pendant l'intro).
private val Navy1 = Color(0xFF0A111F)
private val Navy2 = Color(0xFF05080F)
private val Ink = Color(0xFFEDEFF3)
private val Laser = Color(0xFF00FF66)
private val Turq = Color(0xFF22D3EE)
private val BlueName = Color(0xFF3D7DCA)

@Composable
fun BrandRevealScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    // « Réduire les animations » : si l'échelle d'animation système vaut 0, on
    // n'anime pas et on entre directement dans l'app (accessibilité + confort).
    val animScale = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    val progress = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (animScale == 0f) {
            onFinish(); return@LaunchedEffect
        }
        progress.animateTo(1f, tween(DURATION_MS, easing = LinearEasing))
        onFinish()
    }
    val p = progress.value

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Navy1, Navy2)))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onFinish() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Le symbole + l'anneau + le laser, dessinés au Canvas.
            val markIn = smooth(phase(p, 0.05f, 0.42f))     // apparition (scale + fondu)
            val sweep = smooth(phase(p, 0.34f, 0.70f))       // balayage laser 0->1
            val ring = smooth(phase(p, 0.28f, 0.60f))        // anneau qui se dessine + pulse
            Canvas(
                Modifier
                    .size(184.dp)
                    .graphicsLayer {
                        val s = 0.78f + 0.22f * markIn
                        scaleX = s; scaleY = s
                        alpha = markIn
                    },
            ) {
                drawBrandMark(sweep = sweep, ringProgress = ring)
            }
            Spacer(Modifier.height(28.dp))
            // Logotype : Blok (bleu) + QR (turquoise), fondu + légère montée.
            val wordIn = smooth(phase(p, 0.55f, 0.9f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer {
                    alpha = wordIn
                    translationY = (1f - wordIn) * 24.dp.toPx()
                },
            ) {
                Text("Blok", color = BlueName, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text("QR", color = Turq, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/* ------------------------------ Dessin ----------------------------------- */

/** Balaie le symbole : QR stylisé (finders + données) + cadre de visée + laser. */
private fun DrawScope.drawBrandMark(sweep: Float, ringProgress: Float) {
    val w = size.width
    val c = Offset(w / 2f, w / 2f)
    val mark = w * 0.62f                    // côté du bloc QR
    val left = c.x - mark / 2f
    val top = c.y - mark / 2f
    val cell = mark / 5f

    // Anneau de protection (cercle lumineux) qui se dessine puis reste.
    val ringR = w * 0.46f
    if (ringProgress > 0f) {
        drawCircle(
            color = Laser.copy(alpha = 0.18f * ringProgress),
            radius = ringR,
            center = c,
            style = Stroke(width = w * 0.05f),
        )
        drawCircle(
            color = Laser.copy(alpha = 0.9f * ringProgress),
            radius = ringR,
            center = c,
            style = Stroke(width = w * 0.012f),
        )
    }

    // 3 motifs de détection (coins) + quelques modules.
    finder(Offset(left + cell, top + cell), cell * 1.7f)
    finder(Offset(left + mark - cell, top + cell), cell * 1.7f)
    finder(Offset(left + cell, top + mark - cell), cell * 1.7f)
    val m = cell * 0.66f
    for ((dx, dy) in listOf(3 to 3, 4 to 4, 3 to 4, 4 to 3)) {
        drawRoundRect(
            color = Ink,
            topLeft = Offset(left + dx * cell - m / 2, top + dy * cell - m / 2),
            size = Size(m, m),
            cornerRadius = CornerRadius(m * 0.2f),
        )
    }

    // Cadre de visée (4 crochets) autour du bloc.
    val bl = mark * 0.26f
    val bw = w * 0.02f
    bracket(Offset(left - bw, top - bw), 1f, 1f, bl, bw)
    bracket(Offset(left + mark + bw, top - bw), -1f, 1f, bl, bw)
    bracket(Offset(left - bw, top + mark + bw), 1f, -1f, bl, bw)
    bracket(Offset(left + mark + bw, top + mark + bw), -1f, -1f, bl, bw)

    // Laser vert qui balaie de haut en bas (glow simulé par lignes empilées).
    if (sweep > 0f) {
        val y = top + mark * sweep
        val x0 = left - bw
        val x1 = left + mark + bw
        drawLine(Laser.copy(alpha = 0.22f), Offset(x0, y), Offset(x1, y), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
        drawLine(Laser.copy(alpha = 0.55f), Offset(x0, y), Offset(x1, y), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
        drawLine(Laser, Offset(x0, y), Offset(x1, y), strokeWidth = w * 0.012f, cap = StrokeCap.Round)
        drawLine(Color(0xFFDFFFE9), Offset(x0, y), Offset(x1, y), strokeWidth = w * 0.005f, cap = StrokeCap.Round)
    }
}

/** Motif de détection : anneau + oeil central, en Blanc Cassé. */
private fun DrawScope.finder(center: Offset, s: Float) {
    drawRoundRect(
        color = Ink,
        topLeft = Offset(center.x - s / 2, center.y - s / 2),
        size = Size(s, s),
        cornerRadius = CornerRadius(s * 0.22f),
        style = Stroke(width = s * 0.16f),
    )
    val eye = s * 0.34f
    drawRoundRect(
        color = Ink,
        topLeft = Offset(center.x - eye / 2, center.y - eye / 2),
        size = Size(eye, eye),
        cornerRadius = CornerRadius(eye * 0.25f),
    )
}

/** Crochet de cadrage en L (sx/sy = direction). */
private fun DrawScope.bracket(corner: Offset, sx: Float, sy: Float, len: Float, wdt: Float) {
    drawLine(Ink, corner, Offset(corner.x + sx * len, corner.y), strokeWidth = wdt, cap = StrokeCap.Round)
    drawLine(Ink, corner, Offset(corner.x, corner.y + sy * len), strokeWidth = wdt, cap = StrokeCap.Round)
}

/* ------------------------------ Utilitaires ------------------------------ */

/** Sous-phase [a,b] de la progression globale, ramenée dans [0,1]. */
private fun phase(p: Float, a: Float, b: Float): Float =
    ((p - a) / (b - a)).coerceIn(0f, 1f)

/** Lissage smoothstep (démarrage/fin adoucis). */
private fun smooth(t: Float): Float = t * t * (3f - 2f * t)
