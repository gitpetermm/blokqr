package com.blokqr.app.ui.settings
import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.data.ScanLogStore
import com.blokqr.app.data.SecurityStore
import com.blokqr.app.ui.security.authenticateForAction
import com.blokqr.app.data.SettingsStore
import com.blokqr.app.ui.feedback.FeedbackSection
import com.blokqr.app.ui.theme.ScreenContainer
import com.blokqr.app.ui.theme.SectionCard
import com.blokqr.app.ui.theme.Spacing
import com.blokqr.app.ui.theme.ThemeChoice
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onThemeChange: (ThemeChoice) -> Unit,
    onShowHistory: () -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scanLog = remember { ScanLogStore(context) }
    val security = remember { SecurityStore(context) }
    var selectedTheme by remember { mutableStateOf(store.themeChoice()) }
    var selectedLang by remember { mutableStateOf(store.languageTag()) }
    var historyEnabled by remember { mutableStateOf(scanLog.isEnabled()) }
    var productRecognition by remember { mutableStateOf(store.productRecognitionEnabled()) }
    var productNameLookup by remember { mutableStateOf(store.productNameLookupEnabled()) }
    var historyLock by remember { mutableStateOf(security.isHistoryLockEnabled()) }
    var appLock by remember { mutableStateOf(security.isAppLockEnabled()) }
    var grace by remember { mutableIntStateOf(security.graceSeconds()) }
    var secureScreen by remember { mutableStateOf(security.isSecureScreenEnabled()) }
    ScreenContainer {
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
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        // --- Thème (changement instantané) ---------------------------------
        SectionCard(
            title = stringResource(R.string.settings_theme),
            icon = Icons.Rounded.Palette,
        ) {
            val themes = listOf(
                ThemeChoice.SYSTEM to R.string.theme_system,
                ThemeChoice.LIGHT to R.string.theme_light,
                ThemeChoice.DARK to R.string.theme_dark,
                ThemeChoice.WARM to R.string.theme_warm,
                ThemeChoice.COLD to R.string.theme_cold,
            )
            OptionGroup {
                themes.forEach { (choice, labelRes) ->
                    OptionRow(
                        label = stringResource(labelRes),
                        selected = selectedTheme == choice,
                        onClick = {
                            if (selectedTheme != choice) {
                                selectedTheme = choice
                                onThemeChange(choice) // applique sans redémarrer
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        // --- Langue (recreate pour appliquer la locale) --------------------
        SectionCard(
            title = stringResource(R.string.settings_language),
            icon = Icons.Rounded.Language,
        ) {
            // "" = langue du système (défaut). Tag BCP-47 -> dossier de ressources :
            // "zh-CN" correspond à res/values-zh-rCN.
            val langs = listOf(
                "" to R.string.lang_system,
                "fr" to R.string.lang_fr,
                "en" to R.string.lang_en,
                "es" to R.string.lang_es,
                "pt" to R.string.lang_pt,
                "it" to R.string.lang_it,
                "de" to R.string.lang_de,
                "ar" to R.string.lang_ar,
                "tr" to R.string.lang_tr,
                "hi" to R.string.lang_hi,
                "zh-CN" to R.string.lang_zh,
                "ja" to R.string.lang_ja,
                "ru" to R.string.lang_ru,
            )
            OptionGroup {
                langs.forEach { (tag, labelRes) ->
                    OptionRow(
                        label = stringResource(labelRes),
                        selected = selectedLang == tag,
                        onClick = {
                            if (selectedLang != tag) {
                                selectedLang = tag
                                store.setLanguageTag(tag)
                                (context as? Activity)?.recreate()
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_language_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        // --- Historique des scans (local) ----------------------------------
        SectionCard(
            title = stringResource(R.string.settings_history),
            icon = Icons.Rounded.History,
        ) {
            HistoryNavRow(
                label = stringResource(R.string.settings_history_view),
                onClick = onShowHistory,
            )
            Spacer(Modifier.height(Spacing.xs))
            // Interrupteur d'enregistrement (désactivé = plus aucune écriture).
            ToggleRow(
                label = stringResource(R.string.settings_history_enabled),
                checked = historyEnabled,
                onCheckedChange = {
                    historyEnabled = it
                    scanLog.setEnabled(it)
                },
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_history_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            // Verrou biométrique de l'écran Historique (optionnel).
            // L'ACTIVATION est libre (on ajoute une protection) ; la DÉSACTIVATION
            // exige l'empreinte, sinon on pourrait lever le verrou puis consulter.
            val lockTitle = stringResource(R.string.history_lock_title)
            val lockDisableSubtitle = stringResource(R.string.settings_history_lock_confirm)
            val backLabel = stringResource(R.string.action_back)
            ToggleRow(
                label = stringResource(R.string.settings_history_lock),
                checked = historyLock,
                onCheckedChange = { want ->
                    if (want) {
                        historyLock = true
                        security.setHistoryLockEnabled(true)
                    } else {
                        authenticateForAction(
                            context = context,
                            title = lockTitle,
                            subtitle = lockDisableSubtitle,
                            cancelLabel = backLabel,
                            onSuccess = {
                                historyLock = false
                                security.setHistoryLockEnabled(false)
                            },
                            onCancel = {},
                        )
                    }
                },
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_history_lock_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        // --- Codes produits (reconnaissance locale au scan) ----------------
        SectionCard(
            title = stringResource(R.string.settings_product_recognition),
            icon = Icons.Rounded.Inventory2,
        ) {
            ToggleRow(
                label = stringResource(R.string.settings_product_recognition_toggle),
                checked = productRecognition,
                onCheckedChange = {
                    productRecognition = it
                    store.setProductRecognitionEnabled(it)
                },
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_product_recognition_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            // Recherche EN LIGNE du nom (opt-in, désactivée par défaut).
            ToggleRow(
                label = stringResource(R.string.settings_product_name_toggle),
                checked = productNameLookup,
                onCheckedChange = {
                    productNameLookup = it
                    store.setProductNameLookupEnabled(it)
                },
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_product_name_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        // --- Sécurité (verrou application + écran sécurisé) ----------------
        SectionCard(
            title = stringResource(R.string.settings_security),
            icon = Icons.Rounded.Lock,
        ) {
            // Verrou biométrique de toute l'application (optionnel).
            ToggleRow(
                label = stringResource(R.string.settings_app_lock),
                checked = appLock,
                onCheckedChange = {
                    appLock = it
                    security.setAppLockEnabled(it)
                },
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_app_lock_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Délai de grâce avant re-verrouillage : visible si le verrou est actif.
            if (appLock) {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    stringResource(R.string.settings_lock_grace),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.sm))
                OptionGroup {
                    SecurityStore.GRACE_OPTIONS.forEach { seconds ->
                        val labelRes = when (seconds) {
                            0 -> R.string.lock_grace_immediate
                            10 -> R.string.lock_grace_10
                            20 -> R.string.lock_grace_20
                            else -> R.string.lock_grace_30
                        }
                        OptionRow(
                            label = stringResource(labelRes),
                            selected = grace == seconds,
                            onClick = {
                                if (grace != seconds) {
                                    grace = seconds
                                    security.setGraceSeconds(seconds)
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
            // Écran sécurisé : FLAG_SECURE (s'applique au prochain démarrage).
            ToggleRow(
                label = stringResource(R.string.settings_secure_screen),
                checked = secureScreen,
                onCheckedChange = {
                    secureScreen = it
                    security.setSecureScreenEnabled(it)
                },
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.settings_secure_screen_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        // --- Avis & contact -------------------------------------------------
        FeedbackSection()
        Spacer(Modifier.height(Spacing.xl))
    }
}
/**
 * Conteneur d'un groupe d'options radio : espace régulièrement les lignes pour
 * une meilleure lisibilité et une séparation nette entre les choix.
 */
@Composable
private fun OptionGroup(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        content()
    }
}
@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.md))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
@Composable
private fun HistoryNavRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
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
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}
