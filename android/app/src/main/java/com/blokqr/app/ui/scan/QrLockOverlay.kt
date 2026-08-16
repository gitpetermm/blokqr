package com.blokqr.app.ui.scan
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.hypot
/**
 * État du verrouillage à l'écran.
 * - [Idle]   : rien détecté, le viseur normal est affiché.
 * - [Locked] : un code est verrouillé -> resserrement sur ses vrais coins, coche.
 * [corners] sont en pixels ÉCRAN (déjà mappés depuis le repère image).
 */
sealed interface QrLockState {
    data object Idle : QrLockState
    data class Locked(val corners: List<Offset>) : QrLockState
}
/**
 * Overlay de VERROUILLAGE : cadre qui épouse les 4 coins réels du code, crochets
 * qui se resserrent dessus (effet « chasser / attraper »), puis coche « détecté ».
 * Purement cosmétique : ne déclenche AUCUNE analyse.
 */
@Composable
fun QrLockOverlay(
    state: QrLockState.Locked,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val progress = remember(state) { Animatable(0f) }
    LaunchedEffect(state) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val corners = state.corners
        if (corners.size < 4) return@Canvas
        val p = progress.value
        val centroid = centroidOf(corners)
        // Coins « resserrés » : partent élargis (~35 %) et convergent vers les
        // coins réels quand p -> 1 (effet viseur qui attrape le code).
        val expand = 1.35f
        val animated = corners.map { c ->
            val ex = centroid + (c - centroid) * expand
            lerpOffset(ex, c, p)
        }
        val outline = quadPath(animated)
        drawPath(outline, accent.copy(alpha = 0.90f), style = Stroke(width = 4f))
        drawPath(outline, accent.copy(alpha = 0.12f * p))
        // Crochets d'angle épais, orientés selon les arêtes réelles.
        val arm = averageEdge(corners) * 0.24f
        for (i in 0 until 4) {
            val vertex = animated[i]
            val dirNext = unit(corners[i], corners[(i + 1) % 4])
            val dirPrev = unit(corners[i], corners[(i + 3) % 4])
            drawLine(accent, vertex, vertex + dirNext * arm, strokeWidth = 8f, cap = StrokeCap.Round)
            drawLine(accent, vertex, vertex + dirPrev * arm, strokeWidth = 8f, cap = StrokeCap.Round)
        }
        // Coche « détecté » en fin de resserrement.
        val cp = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (cp > 0f) {
            val r = averageEdge(corners) * 0.16f * (0.7f + 0.3f * cp)
            drawCircle(accent.copy(alpha = cp), radius = r, center = centroid)
            val a = centroid + Offset(-r * 0.42f, r * 0.02f)
            val b = centroid + Offset(-r * 0.08f, r * 0.34f)
            val c = centroid + Offset(r * 0.44f, -r * 0.34f)
            drawLine(Color.White.copy(alpha = cp), a, b, strokeWidth = 6f, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = cp), b, c, strokeWidth = 6f, cap = StrokeCap.Round)
        }
    }
}
/* --------------------------- Helpers géométrie --------------------------- */
private fun centroidOf(corners: List<Offset>): Offset =
    Offset(corners.map { it.x }.average().toFloat(), corners.map { it.y }.average().toFloat())
private fun quadPath(corners: List<Offset>): Path = Path().apply {
    moveTo(corners[0].x, corners[0].y)
    for (i in 1 until 4) lineTo(corners[i].x, corners[i].y)
    close()
}
private fun lerpOffset(a: Offset, b: Offset, t: Float): Offset =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
private fun unit(from: Offset, to: Offset): Offset {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy)
    return if (len <= 0f) Offset.Zero else Offset(dx / len, dy / len)
}
private fun averageEdge(corners: List<Offset>): Float {
    var sum = 0f
    for (i in 0 until 4) {
        val a = corners[i]
        val b = corners[(i + 1) % 4]
        sum += hypot(b.x - a.x, b.y - a.y)
    }
    return sum / 4f
}
