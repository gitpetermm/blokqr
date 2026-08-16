package com.blokqr.app.ui.feedback

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.ui.theme.SectionCard
import com.blokqr.app.ui.theme.Spacing

/**
 * Carte « Votre avis » réutilisable (À propos + Paramètres).
 *
 * 👍 J'aime      -> In-App Review natif (avis PUBLIC sur le Play Store)
 * 👎 À améliorer -> email PRIVÉ vers contact@blokqr.com
 * + deux lignes toujours visibles (Noter sur Google Play / Nous contacter)
 *   pour garantir l'absence de « review gating » (conformité Play).
 */
@Composable
fun FeedbackSection() {
    val context = LocalContext.current
    val activity = context as? Activity

    SectionCard(
        title = stringResource(R.string.feedback_title),
        icon = Icons.Rounded.Star,
    ) {
        Text(
            stringResource(R.string.feedback_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(Spacing.md))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ChoiceButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ThumbUp,
                label = stringResource(R.string.feedback_like),
                onClick = {
                    if (activity != null) launchInAppReview(activity)
                    else context.openPlayStoreListing()
                },
            )
            ChoiceButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ThumbDown,
                label = stringResource(R.string.feedback_dislike),
                onClick = { context.sendFeedbackEmail() },
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(Spacing.xs))

        // Toujours accessibles, sans condition (pas de gating).
        ActionRow(
            icon = Icons.Rounded.Star,
            label = stringResource(R.string.feedback_rate_play),
            onClick = { context.openPlayStoreListing() },
        )
        Spacer(Modifier.height(Spacing.xs))
        ActionRow(
            icon = Icons.Rounded.Email,
            label = stringResource(R.string.feedback_contact),
            onClick = { context.sendFeedbackEmail() },
        )
    }
}

@Composable
private fun ChoiceButton(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
