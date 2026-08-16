package com.blokqr.app.ui.legal
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.blokqr.app.R
import com.blokqr.app.ui.theme.ScreenContainer
import com.blokqr.app.ui.theme.Spacing
/**
 * Politique de confidentialité affichée DANS l'application (hors-ligne).
 *
 * Tout le texte provient des ressources `privacy_*` (localisées) : aucune
 * connexion externe n'est nécessaire — cohérent avec le principe « le lien
 * n'est jamais ouvert sur votre téléphone ». L'URL publique reste exigée par
 * Google Play (métadonnée de la console), mais l'app ne l'ouvre pas.
 */
@Composable
fun PrivacyPolicyScreen(onClose: () -> Unit) {
    ScreenContainer(horizontalAlignment = Alignment.Start) {
        // En-tête avec retour.
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                stringResource(R.string.privacy_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            stringResource(R.string.privacy_updated),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            stringResource(R.string.privacy_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Section(R.string.privacy_s1_title, R.string.privacy_s1_body)
        Section(R.string.privacy_s2_title, R.string.privacy_s2_body)
        Section(R.string.privacy_s3_title, R.string.privacy_s3_body)
        Section(R.string.privacy_s4_title, R.string.privacy_s4_body)
        Section(R.string.privacy_s5_title, R.string.privacy_s5_body)
        Section(R.string.privacy_s6_title, R.string.privacy_s6_body)
        Section(R.string.privacy_s7_title, R.string.privacy_s7_body)
        Section(R.string.privacy_s8_title, R.string.privacy_s8_body)
        // v2.0.0 : nouveaux comportements de données.
        Section(R.string.privacy_s9_title, R.string.privacy_s9_body)
        Section(R.string.privacy_s10_title, R.string.privacy_s10_body)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            stringResource(R.string.privacy_contact),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}
@Composable
private fun Section(@StringRes titleRes: Int, @StringRes bodyRes: Int) {
    Spacer(Modifier.height(Spacing.lg))
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(Spacing.xs))
    Text(
        stringResource(bodyRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
