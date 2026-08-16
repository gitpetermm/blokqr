package com.blokqr.app.ui.history
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blokqr.app.R
import com.blokqr.app.data.ScanLogEntry
import com.blokqr.app.ui.provenance.GtinDecoder
import com.blokqr.app.model.Verdict
import com.blokqr.app.ui.theme.*
/**
 * Écran Historique des scans (LOCAL). Liste du plus récent au plus ancien, avec
 * RECHERCHE (sur la valeur) et FILTRES par catégorie ; tap sur une entrée ->
 * relance l'analyse ; icône de suppression par entrée ; effacement global (avec
 * confirmation). Rappelle implicitement la confidentialité : aucune de ces
 * données ne quitte l'appareil.
 */
@Composable
fun ScanHistoryScreen(
    onClose: () -> Unit,
    onReplay: (String, String) -> Unit,
    vm: ScanHistoryViewModel = viewModel(),
) {
    val allEntries by vm.entries.collectAsState()
    val entries by vm.visible.collectAsState()
    val loading by vm.loading.collectAsState()
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.reload() }
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
                stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = Spacing.sm),
            )
            if (allEntries.isNotEmpty()) {
                IconButton(onClick = { showConfirm = true }) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        stringResource(R.string.history_clear),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        // Recherche + filtres : affichés seulement s'il y a de l'historique.
        if (allEntries.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.history_search_clear))
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.history_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                FilterPill(ScanHistoryViewModel.Filter.ALL, filter, vm, R.string.history_filter_all)
                FilterPill(ScanHistoryViewModel.Filter.SAFE, filter, vm, R.string.history_filter_safe)
                FilterPill(ScanHistoryViewModel.Filter.SUSPECT, filter, vm, R.string.history_filter_suspect)
                FilterPill(ScanHistoryViewModel.Filter.DANGEROUS, filter, vm, R.string.history_filter_dangerous)
                FilterPill(ScanHistoryViewModel.Filter.PRODUCT, filter, vm, R.string.history_filter_product)
            }
        }
        Spacer(Modifier.height(Spacing.md))
        when {
            loading -> {
                Spacer(Modifier.height(Spacing.xxl))
                CircularProgressIndicator(color = Blue)
            }
            allEntries.isEmpty() -> EmptyState()
            entries.isEmpty() -> NoMatchState()
            else -> entries.forEach { entry ->
                HistoryRow(
                    entry = entry,
                    onClick = { onReplay(entry.value, entry.symbology) },
                    onDelete = { vm.remove(entry) },
                )
            }
        }
        Spacer(Modifier.height(Spacing.xl))
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.history_clear_confirm_title)) },
            text = { Text(stringResource(R.string.history_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    vm.clear()
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
private fun FilterPill(
    value: ScanHistoryViewModel.Filter,
    current: ScanHistoryViewModel.Filter,
    vm: ScanHistoryViewModel,
    labelRes: Int,
) {
    FilterChip(
        selected = current == value,
        onClick = { vm.setFilter(value) },
        label = { Text(stringResource(labelRes)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Blue.copy(alpha = 0.16f),
            selectedLabelColor = Blue,
        ),
    )
}
@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Rounded.History, Blue)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            stringResource(R.string.history_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
@Composable
private fun NoMatchState() {
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.history_no_match),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
@Composable
private fun HistoryRow(
    entry: ScanLogEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // Une entrée PRODUIT se reconnaît a sa symbologie (EAN/UPC) : marquage dédié
    // au moment de l'affichage, sans champ supplémentaire dans le store.
    val isProduct = remember(entry.symbology) { GtinDecoder.isProductSymbology(entry.symbology) }
    val verdict = remember(entry.verdict) {
        runCatching { Verdict.valueOf(entry.verdict) }.getOrDefault(Verdict.UNKNOWN)
    }
    val time = remember(entry.timestamp) {
        DateUtils.getRelativeTimeSpanString(
            entry.timestamp,
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
        if (isProduct) {
            Icon(
                Icons.Rounded.Inventory2, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Icon(
                verdict.icon(), null,
                tint = verdict.color,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                entry.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val label = if (isProduct) {
                stringResource(R.string.history_type_product)
            } else {
                stringResource(verdictLabelRes(verdict))
            }
            Text(
                label + "  ·  " + entry.symbology + "  ·  " + time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Suppression unitaire de l'entrée (zone tactile confortable).
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.DeleteOutline,
                stringResource(R.string.history_delete_one),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
/** Réutilise les libellés de verdict existants (cohérence avec ResultScreen). */
private fun verdictLabelRes(v: Verdict): Int = when (v) {
    Verdict.SAFE -> R.string.result_verdict_safe
    Verdict.DANGEROUS -> R.string.result_verdict_caution
    Verdict.MALICIOUS -> R.string.result_verdict_malicious
    Verdict.UNKNOWN -> R.string.result_verdict_unknown
    Verdict.NEUTRAL -> R.string.result_verdict_neutral
}