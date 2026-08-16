package com.blokqr.app.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.ui.theme.Dimens
import com.blokqr.app.ui.theme.IconBadge
import com.blokqr.app.ui.theme.PrimaryButton
import com.blokqr.app.ui.theme.Spacing
/**
 * Écran d'échec d'analyse (réseau, délai dépassé, vérification de signature
 * impossible…). Le message technique est affiché tel quel ; on rappelle qu'aucun
 * lien n'a été ouvert, puis on propose de relancer l'analyse.
 *
 * Le contenu est défilable : un message long ou une grande taille de police ne
 * doit jamais rendre le bouton « Réessayer » inatteignable (accessibilité).
 */
@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenPadding, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(Icons.Rounded.CloudOff, cs.error)
            Spacer(Modifier.height(Spacing.xl))
            Text(
                stringResource(R.string.error_title),
                style = MaterialTheme.typography.headlineSmall,
                color = cs.onSurface,
                textAlign = TextAlign.Center,
            )
            if (message.isNotBlank()) {
                Spacer(Modifier.height(Spacing.md))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.5f))
                        .padding(Spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            Text(
                stringResource(R.string.error_safe_note),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xl))
            PrimaryButton(
                text = stringResource(R.string.error_retry),
                onClick = onRetry,
                icon = Icons.Rounded.Refresh,
            )
        }
    }
}