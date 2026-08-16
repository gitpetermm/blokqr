package com.blokqr.app.ui.quota

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.security.QuotaSnapshot
import com.blokqr.app.ui.theme.Blue
import com.blokqr.app.ui.theme.PrimaryButton
import com.blokqr.app.ui.theme.ScreenContainer
import com.blokqr.app.ui.theme.SecondaryButton
import com.blokqr.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Ecran PLEIN ECRAN affiche au premier depassement du quota quotidien (Free).
 *
 * Conforme a la decision « UX progressive » : ce plein ecran n'apparait qu'au
 * PREMIER depassement. Les scans suivants retombent sur un bandeau + analyse
 * locale degradee (gere dans ScanViewModel / ResultScreen).
 *
 * Contenu :
 *   - message de confiance : meme moteur de detection avec ou sans Pro ;
 *   - compte a rebours jusqu'au reset (affiche en duree relative locale) ;
 *   - CTA primaire : passer Pro (analyses illimitees*, jusqu'a 500/j) ;
 *   - action secondaire : continuer avec une analyse locale limitee ;
 *   - lien : reessayer plus tard (ferme l'ecran, retour au scanner).
 */
@Composable
fun QuotaExhaustedScreen(
    snapshot: QuotaSnapshot,
    onUpgrade: () -> Unit,
    onContinueLocal: () -> Unit,
    onClose: () -> Unit,
) {
    ScreenContainer(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(Spacing.xl))

        Icon(
            Icons.Rounded.HourglassEmpty,
            contentDescription = null,
            tint = Blue,
            modifier = Modifier.height(56.dp),
        )

        Spacer(Modifier.height(Spacing.lg))

        Text(
            stringResource(R.string.quota_exhausted_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.sm))

        Text(
            stringResource(R.string.quota_exhausted_body, snapshot.limit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.sm))

        // Note de confiance : la securite reste entiere (meme moteur de
        // detection avec ou sans Pro). Transforme le moment de frustration
        // (quota atteint) en reassurance plutot qu'en doute sur la protection.
        Text(
            stringResource(R.string.quota_same_engine),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.md))

        // Compte a rebours « live » jusqu'au reset (recalcule chaque seconde).
        val remaining by produceState(initialValue = snapshot.resetInSeconds) {
            var s = snapshot.resetInSeconds
            while (s > 0) {
                value = s
                delay(1000)
                s -= 1
            }
            value = 0
        }
        Text(
            stringResource(R.string.quota_exhausted_reset_in, formatDuration(remaining)),
            style = MaterialTheme.typography.bodyLarge,
            color = Blue,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.xl))

        // CTA primaire : passer Pro.
        PrimaryButton(
            text = stringResource(R.string.quota_exhausted_cta_pro),
            onClick = onUpgrade,
        )

        Spacer(Modifier.height(Spacing.md))

        // Action secondaire : continuer en analyse locale (mode degrade).
        SecondaryButton(
            text = stringResource(R.string.quota_exhausted_cta_local),
            onClick = onContinueLocal,
            icon = Icons.Rounded.Bolt,
            accent = Blue,
        )

        Spacer(Modifier.height(Spacing.sm))

        // Lien discret : reessayer plus tard (ferme, retour scanner).
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.quota_exhausted_cta_later))
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}

/**
 * Formate une duree (secondes) en « Xh Ymin » ou « Ymin » ou « Zs ».
 * Affichage relatif simple, independant du fuseau (c'est une DUREE, pas une
 * heure absolue) — donc pas de souci de conversion UTC/local ici.
 */
private fun formatDuration(totalSeconds: Int): String {
    if (totalSeconds <= 0) return "0s"
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "${h}h ${m}min"
        m > 0 -> "${m}min"
        else -> "${s}s"
    }
}