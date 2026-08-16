package com.blokqr.app.ui.tools
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.data.CreateHistoryStore
import com.blokqr.app.data.CreatedCode
import com.blokqr.app.ui.theme.Blue
import com.blokqr.app.ui.theme.IconBadge
import com.blokqr.app.ui.theme.PrimaryButton
import com.blokqr.app.ui.theme.ScreenContainer
import com.blokqr.app.ui.theme.SecondaryButton
import com.blokqr.app.ui.theme.Spacing
import kotlinx.coroutines.launch
/**
 * Historique des codes CRÉÉS (onglet Créer). Liste chiffrée (voir
 * CreateHistoryStore) ; toucher une entrée régénère le code depuis son payload
 * (aucune image stockée) et propose partage/impression/suppression.
 *
 * `BackHandler` local : le retour système referme le détail puis l'historique
 * (priorité au gestionnaire le plus interne), sans quitter l'onglet Créer.
 */
@Composable
fun CreateHistoryScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { CreateHistoryStore(context) }
    var entries by remember { mutableStateOf<List<CreatedCode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var enabled by remember { mutableStateOf(store.isEnabled()) }
    var selected by remember { mutableStateOf<CreatedCode?>(null) }
    var showConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entries = store.list()
        loading = false
    }
    // Vue détail : code régénéré + actions.
    selected?.let { code ->
        CreatedCodeDetail(
            code = code,
            onBack = { selected = null },
            onDelete = {
                scope.launch {
                    store.delete(code)
                    entries = store.list()
                    selected = null
                }
            },
        )
        return
    }
    BackHandler(onBack = onClose)
    ScreenContainer {
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                stringResource(R.string.create_history_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = Spacing.sm),
            )
            if (entries.isNotEmpty()) {
                IconButton(onClick = { showConfirm = true }) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        stringResource(R.string.history_clear),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        // Interrupteur d'enregistrement + avertissement (mots de passe Wi-Fi).
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.create_history_enabled),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    store.setEnabled(it)
                },
            )
        }
        Text(
            stringResource(R.string.create_history_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        when {
            loading -> {
                Spacer(Modifier.height(Spacing.xxl))
                CircularProgressIndicator(color = Blue)
            }
            entries.isEmpty() -> EmptyState()
            else -> entries.forEach { code ->
                CreatedRow(code = code, onClick = { selected = code })
            }
        }
        Spacer(Modifier.height(Spacing.xl))
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.history_clear_confirm_title)) },
            text = { Text(stringResource(R.string.create_history_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    scope.launch {
                        store.clear()
                        entries = store.list()
                    }
                }) { Text(stringResource(R.string.history_clear_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }
}
@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Rounded.QrCode2, Blue)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            stringResource(R.string.create_history_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
@Composable
private fun CreatedRow(code: CreatedCode, onClick: () -> Unit) {
    val time = remember(code.timestamp) {
        DateUtils.getRelativeTimeSpanString(
            code.timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.QrCode2, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                code.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // Nom de format lisible (identifiants standard non traduits).
                code.format.replace('_', '-') + "  ·  " + time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
@Composable
private fun CreatedCodeDetail(
    code: CreatedCode,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    // Régénération à partir du payload (aucune image n'est stockée).
    val format = remember(code.format) {
        runCatching { CodeFormat.valueOf(code.format) }.getOrNull()
    }
    val bitmap = remember(code.payload, code.format) {
        format?.let { CodeEncoder.encodeOrNull(code.payload, it, if (it.matrix) 600 else 800) }
    }
    BackHandler(onBack = onBack)
    ScreenContainer(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                code.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = Spacing.sm),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        CodePreview(bitmap = bitmap, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth())
        Spacer(Modifier.height(Spacing.lg))
        if (bitmap != null) {
            PrimaryButton(
                text = stringResource(R.string.result_action_share),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { CodeExport.share(context, bitmap) }
                },
                icon = Icons.Rounded.Share,
            )
            Spacer(Modifier.height(Spacing.sm))
            SecondaryButton(
                text = stringResource(R.string.tools_action_print),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    CodeExport.print(context, bitmap)
                },
                icon = Icons.Rounded.Print,
                accent = Blue,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton(
            text = stringResource(R.string.create_history_delete),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDelete()
            },
            icon = Icons.Rounded.Delete,
            accent = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}
