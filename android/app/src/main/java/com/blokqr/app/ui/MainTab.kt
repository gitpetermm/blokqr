package com.blokqr.app.ui
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
/** Onglets principaux : le Scanner reconnaît tout (sécurité ET produits), donc
 *  deux onglets suffisent — plus besoin d'un onglet Provenance dédié. */
enum class MainTab(val icon: ImageVector, val labelRes: Int) {
    SCANNER(Icons.Rounded.QrCodeScanner, R.string.tab_scanner),
    CREATE(Icons.Rounded.QrCode2, R.string.tab_create),
}
/**
 * Barre d'onglets : PASTILLE FLOTTANTE centrée (pas une banque pleine largeur).
 * Fond translucide adapté au thème (surfaceVariant), segment actif en primary.
 * Cohérent avec l'esthétique légère des boutons superposés (Infos/Paramètres).
 */
@Composable
fun MainBottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = cs.surfaceVariant.copy(alpha = 0.92f),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier.padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MainTab.values().forEach { tab ->
                    TabPill(tab = tab, selected = selected == tab, onClick = { onSelect(tab) })
                }
            }
        }
    }
}
@Composable
private fun TabPill(tab: MainTab, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Transition douce de la sélection (couleur du fond et du contenu).
    val bg by animateColorAsState(
        targetValue = if (selected) cs.primary else Color.Transparent,
        label = "tabPillBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) cs.onPrimary else cs.onSurfaceVariant,
        label = "tabPillFg",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tab.icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(tab.labelRes),
            color = fg,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
