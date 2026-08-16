package com.blokqr.app.ui.analyze

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.ui.theme.Dimens
import com.blokqr.app.ui.theme.IconBadge
import com.blokqr.app.ui.theme.Spacing

private data class AnalyzeStep(val icon: ImageVector, val labelRes: Int)

/**
 * Écran d'attente pendant l'analyse distante.
 * Purement visuel : aucune logique réseau ici. Les étapes affichées défilent en
 * boucle pour matérialiser la progression (décodage → redirections → bac à sable
 * → signature) sans dépendre de l'état réel du pipeline.
 */
@Composable
fun AnalyzingScreen(rawPreview: String, retrying: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val steps = listOf(
        AnalyzeStep(Icons.Rounded.QrCodeScanner, R.string.analyzing_step_decode),
        AnalyzeStep(Icons.AutoMirrored.Rounded.AltRoute, R.string.analyzing_step_resolve),
        AnalyzeStep(Icons.Rounded.Science, R.string.analyzing_step_inspect),
        AnalyzeStep(Icons.Rounded.VerifiedUser, R.string.analyzing_step_verify),
    )

    val transition = rememberInfiniteTransition(label = "analyze")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "angle",
    )
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = steps.size.toFloat(),
        animationSpec = infiniteRepeatable(tween(steps.size * 900, easing = LinearEasing)),
        label = "phase",
    )
    val active = phase.toInt().coerceIn(0, steps.lastIndex)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.background, cs.surface, cs.background)))
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = Dimens.contentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = Dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                IconBadge(Icons.Rounded.Shield, cs.primary, size = Dimens.verdictBadge)
                Canvas(Modifier.size(Dimens.verdictBadge + 18.dp)) {
                    val stroke = 6f
                    val tl = Offset(stroke, stroke)
                    val sz = Size(size.width - 2 * stroke, size.height - 2 * stroke)
                    drawArc(
                        color = cs.primary.copy(alpha = 0.15f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = tl, size = sz,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = cs.primary,
                        startAngle = angle, sweepAngle = 80f, useCenter = false,
                        topLeft = tl, size = sz,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xl))
            Text(
                stringResource(R.string.analyzing_title),
                style = MaterialTheme.typography.headlineSmall,
                color = cs.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.analyzing_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (retrying) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    stringResource(R.string.analyzing_retry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.primary,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(Spacing.xl))
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                steps.forEachIndexed { i, s ->
                    val reached = i <= active
                    val tint = if (reached) cs.primary else cs.outline
                    val textColor = when {
                        i == active -> cs.onSurface
                        reached -> cs.onSurfaceVariant
                        else -> cs.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(s.icon, null, tint = tint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            stringResource(s.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                        )
                    }
                }
            }

            if (rawPreview.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xl))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.5f))
                        .padding(Spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.analyzing_target),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        rawPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
