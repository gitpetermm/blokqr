package com.blokqr.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blokqr.app.model.Verdict

/* Conteneur d'écran responsive : fond dégradé issu du thème, contenu centré
 * et borné en largeur, défilement et marges système. */
@Composable
fun ScreenContainer(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.background, cs.surface, cs.background)))
            .systemBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val base = Modifier
            .widthIn(max = Dimens.contentMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = Dimens.screenPadding)
        val colMod = if (scrollable) base.verticalScroll(rememberScrollState()) else base
        Column(modifier = colMod.then(modifier), horizontalAlignment = horizontalAlignment) {
            content()
        }
    }
}

/* Carte de section uniforme. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.secondary,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            content()
        }
    }
}

@Composable
fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

@Composable
fun IconBadge(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.verdictBadge,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.08f)),
                ),
            )
            .border(2.dp, color.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun RiskMeter(score: Int, color: Color, label: String, modifier: Modifier = Modifier) {
    val pct = score.coerceIn(0, 100) / 100f
    val track = MaterialTheme.colorScheme.outline
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$score / 100", style = MaterialTheme.typography.labelLarge, color = color)
        }
        Spacer(Modifier.height(Spacing.sm))
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val r = size.height / 2f
            drawRoundRect(color = track, cornerRadius = CornerRadius(r, r))
            if (pct > 0f) {
                drawRoundRect(
                    color = color,
                    size = Size(size.width * pct, size.height),
                    cornerRadius = CornerRadius(r, r),
                )
            }
        }
    }
}

@Composable
fun TrustBadge(verified: Boolean, verifiedLabel: String, unverifiedLabel: String, modifier: Modifier = Modifier) {
    val c = if (verified) SafeGreen else DangerAmber
    val icon = if (verified) Icons.Rounded.Verified else Icons.Rounded.WarningAmber
    val label = if (verified) verifiedLabel else unverifiedLabel
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.14f))
            .border(1.dp, c.copy(alpha = 0.40f), RoundedCornerShape(50))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c)
    }
}

@Composable
fun InfoChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = Spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = Dimens.actionHeight),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(Spacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.secondary,
) {
    OutlinedButton(
        onClick = onClick,
		modifier = modifier.fillMaxWidth().heightIn(min = Dimens.actionHeight),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(Spacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

fun Verdict.icon(): ImageVector = when (this) {
    Verdict.SAFE -> Icons.Rounded.VerifiedUser
    Verdict.DANGEROUS -> Icons.Rounded.WarningAmber
    Verdict.MALICIOUS -> Icons.Rounded.Block
    Verdict.UNKNOWN -> Icons.AutoMirrored.Rounded.HelpOutline
    Verdict.NEUTRAL -> Icons.Rounded.Info
}
