package com.blokqr.app.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.billing.EntitlementTier
import com.blokqr.app.billing.EntitlementUiState
import com.blokqr.app.ui.theme.Blue
import com.blokqr.app.ui.theme.BlueBright
import com.blokqr.app.ui.theme.DangerAmber
import com.blokqr.app.ui.theme.Navy
/**
 * Badge d'etat d'abonnement, affiche sous le nom « BlokQR ».
 *
 * Trois etats derives de EntitlementManager.ui :
 *   - FREE  : pastille discrete (translucide) ; clic -> paywall.
 *   - TRIAL : pastille ambre « Essai · N j » ; clic -> paywall.
 *   - PRO   : pastille degradee bleue avec etoile (non cliquable).
 *
 * Accessibilite : les etats cliquables (FREE/TRIAL) exposent le role Button
 * pour que TalkBack les annonce comme des boutons (et non du simple texte).
 *
 * Rappel : ce badge est purement informatif. L'acces reel a l'analyse profonde
 * reste impose cote serveur (entitlement signe verifie sur /v1/analyze/deep).
 */
@Composable
fun EntitlementBadge(
    state: EntitlementUiState,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.tier) {
        EntitlementTier.PRO -> BadgePill(
            label = stringResource(R.string.badge_pro),
            icon = Icons.Rounded.Star,
            background = Brush.horizontalGradient(listOf(Blue, BlueBright)),
            content = Color.White,
            modifier = modifier,
        )
        EntitlementTier.TRIAL -> BadgePill(
            label = if (state.trialDaysLeft > 0)
                stringResource(R.string.badge_trial_days, state.trialDaysLeft)
            else stringResource(R.string.badge_trial),
            icon = Icons.Rounded.Bolt,
            background = SolidColor(DangerAmber),
            content = Navy,
            modifier = modifier.clickable(role = Role.Button, onClick = onUpgrade),
        )
        EntitlementTier.FREE -> BadgePill(
            label = stringResource(R.string.badge_free),
            icon = null,
            background = SolidColor(Color.Black.copy(alpha = 0.38f)),
            content = Color.White,
            modifier = modifier.clickable(role = Role.Button, onClick = onUpgrade),
        )
    }
}
@Composable
private fun BadgePill(
    label: String,
    icon: ImageVector?,
    background: Brush,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}