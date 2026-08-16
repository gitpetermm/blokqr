package com.blokqr.app.ui.provenance
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.data.OpenFoodFactsClient
import com.blokqr.app.data.SettingsStore
import com.blokqr.app.ui.theme.IconBadge
import com.blokqr.app.ui.theme.PrimaryButton
import com.blokqr.app.ui.theme.Spacing
import com.blokqr.app.ui.util.openUrlExternally
import java.util.Locale
/**
 * Fiche de reconnaissance produit (GTIN + organisation GS1 émettrice), PARTAGÉE
 * entre l'onglet Provenance et le Scanner. Décodage 100 % local, AUCUNE analyse
 * de sécurité. [informational] = true quand la fiche est affichée depuis le
 * Scanner : elle ajoute alors une note « à titre informatif ».
 */
@Composable
fun ProductProvenanceCard(
    info: GtinInfo,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    informational: Boolean = false,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val issuer = info.issuer
    val nameLookup = remember { SettingsStore(context).productNameLookupEnabled() }
    Column(
        modifier = modifier
            .background(cs.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Rounded.Inventory2, cs.primary)
        Spacer(Modifier.height(Spacing.md))
        if (informational) {
            Text(
                stringResource(R.string.provenance_info_note),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.md))
        }
        if (nameLookup) {
            ProductNameSection(info.gtin)
            Spacer(Modifier.height(Spacing.md))
        }
        Text(
            text = issuer?.let { issuerLabel(context, it) } ?: "—",
            style = MaterialTheme.typography.headlineSmall,
            color = cs.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.lg))
        DetailRow(stringResource(R.string.provenance_gtin), info.gtin)
        DetailRow(stringResource(R.string.provenance_format), info.format)
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (info.validCheck) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (info.validCheck) cs.primary else cs.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                stringResource(
                    if (info.validCheck) R.string.provenance_check_valid
                    else R.string.provenance_check_invalid,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }
        if (issuer?.kind == Gs1Kind.MEMBER_ORG) {
            Spacer(Modifier.height(Spacing.lg))
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.errorContainer.copy(alpha = 0.5f))
                    .padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.WarningAmber, null, tint = cs.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    stringResource(R.string.provenance_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurface,
                )
            }
        }
        Spacer(Modifier.height(Spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(summary(context, info)))
                    Toast.makeText(
                        context,
                        context.getString(R.string.provenance_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            ) {
                Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.provenance_copy))
            }
            OutlinedButton(onClick = { shareText(context, summary(context, info)) }) {
                Icon(Icons.Rounded.Share, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.provenance_share))
            }
        }
        Spacer(Modifier.height(Spacing.xl))
        PrimaryButton(
            text = stringResource(R.string.provenance_rescan),
            onClick = onClose,
            icon = Icons.Rounded.QrCodeScanner,
            enabled = true,
        )
    }
}
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}
/** Libellé de l'émetteur : nom de pays LOCALISÉ ou texte de catégorie. */
private fun issuerLabel(context: Context, entry: Gs1Entry): String = when (entry.kind) {
    Gs1Kind.MEMBER_ORG -> {
        val iso = entry.iso
        if (iso != null) {
            val locale = context.resources.configuration.locales[0]
            val name = runCatching {
                Locale.Builder().setRegion(iso).build().getDisplayCountry(locale)
            }.getOrNull()?.ifBlank { iso } ?: iso
            "GS1 $name"
        } else {
            context.getString(R.string.provenance_gs1_generic)
        }
    }
    Gs1Kind.GS1_GLOBAL -> context.getString(R.string.provenance_gs1_global)
    Gs1Kind.RESTRICTED -> context.getString(R.string.provenance_kind_restricted)
    Gs1Kind.ISBN -> context.getString(R.string.provenance_kind_isbn)
    Gs1Kind.ISSN -> context.getString(R.string.provenance_kind_issn)
    Gs1Kind.COUPON -> context.getString(R.string.provenance_kind_coupon)
    Gs1Kind.UNKNOWN -> context.getString(R.string.provenance_kind_unknown)
}
/** Résumé textuel pour la copie et le partage. */
private fun summary(context: Context, info: GtinInfo): String {
    val issuer = info.issuer?.let { issuerLabel(context, it) } ?: "—"
    val check = context.getString(
        if (info.validCheck) R.string.provenance_check_valid else R.string.provenance_check_invalid,
    )
    return buildString {
        appendLine("BlokQR — ${context.getString(R.string.tab_provenance)}")
        appendLine("${context.getString(R.string.provenance_gtin)} : ${info.gtin} (${info.format})")
        appendLine("${context.getString(R.string.provenance_issuer)} : $issuer")
        append(check)
    }
}
private fun shareText(context: Context, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, null))
    }
}

/**
 * Nom du produit via Open Food Facts (OPT-IN). Recherche asynchrone : indicateur
 * de chargement, puis nom + marque + attribution ; introuvable -> rien affiché.
 */
@Composable
private fun ProductNameSection(gtin: String) {
    val cs = MaterialTheme.colorScheme
    var loading by remember(gtin) { mutableStateOf(true) }
    var result by remember(gtin) { mutableStateOf<OpenFoodFactsClient.ProductName?>(null) }
    LaunchedEffect(gtin) {
        loading = true
        result = OpenFoodFactsClient.lookup(gtin)
        loading = false
    }
    val name = result?.name
    when {
        loading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    stringResource(R.string.provenance_name_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        !name.isNullOrBlank() -> {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onBackground,
                textAlign = TextAlign.Center,
            )
            result?.brand?.takeIf { it.isNotBlank() }?.let { brand ->
                Text(
                    brand,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            val context = LocalContext.current
            // Attribution ODbL cliquable : ouvre la fiche produit sur Open Food
            // Facts (conforme à la licence + permet de corriger une erreur).
            Text(
                stringResource(R.string.provenance_name_source),
                style = MaterialTheme.typography.labelSmall,
                color = cs.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) {
                        context.openUrlExternally(
                            "https://world.openfoodfacts.org/product/$gtin",
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
