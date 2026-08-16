package com.blokqr.app.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.model.LocalReason
import com.blokqr.app.security.QuotaSnapshot
import com.blokqr.app.ui.theme.Blue
import com.blokqr.app.ui.theme.DangerAmber
import com.blokqr.app.ui.theme.Spacing

/**
 * Bandeau inline affiché en haut de ResultScreen.
 *
 * Deux usages :
 *   1. QuotaBanner(snapshot) : avertit qu'il reste peu de scans (near limit).
 *   2. LocalModeBanner(reason) : signale qu'un résultat provient de l'analyse
 *      LOCALE dégradée (non vérifiée par le serveur). C'est un garde-fou de
 *      sécurité : l'utilisateur doit savoir que ce verdict n'est pas signé.
 *      Le `reason` adapte le texte (hors-ligne, initialisation, serveur, quota).
 */

/** Bandeau « il vous reste N analyses aujourd'hui » (near limit, cliquable -> Pro). */
@Composable
fun QuotaBanner(
    snapshot: QuotaSnapshot,
    onUpgrade: () -> Unit,
) {
    if (snapshot.isPro || !snapshot.isNearLimit) return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onUpgrade)
            .background(Blue.copy(alpha = 0.08f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Info, null,
            tint = Blue,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            stringResource(R.string.quota_banner_remaining, snapshot.remaining),
            style = MaterialTheme.typography.bodySmall,
            color = Blue,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Bandeau « analyse locale — non vérifiée ». Affiché quand le résultat vient
 * du LocalAnalyzer (mode dégradé). GARDE-FOU : l'utilisateur ne doit jamais
 * croire qu'un verdict local non signé équivaut à une vérification serveur
 * complète.
 *
 * @param reason cause de la bascule en local. `null` -> message générique
 *               historique (rétro-compatible avec les appels existants).
 */
@Composable
fun LocalModeBanner(reason: LocalReason? = null) {
    val icon: ImageVector
    val textRes: Int
    when (reason) {
        LocalReason.OFFLINE -> {
            icon = Icons.Rounded.WifiOff
            textRes = R.string.local_notice_offline
        }
        LocalReason.INITIALIZING -> {
            icon = Icons.Rounded.Sync
            textRes = R.string.local_notice_initializing
        }
        LocalReason.SERVER_UNAVAILABLE -> {
            icon = Icons.Rounded.CloudOff
            textRes = R.string.local_notice_server
        }
        LocalReason.QUOTA_EXHAUSTED -> {
            icon = Icons.Rounded.HourglassEmpty
            textRes = R.string.local_notice_quota
        }
        null -> {
            icon = Icons.Rounded.CloudOff
            textRes = R.string.quota_local_mode_notice
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(DangerAmber.copy(alpha = 0.12f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            tint = DangerAmber,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            stringResource(textRes),
            style = MaterialTheme.typography.bodySmall,
            color = DangerAmber,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}