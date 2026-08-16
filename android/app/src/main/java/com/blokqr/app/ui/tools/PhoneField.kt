package com.blokqr.app.ui.tools
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.blokqr.app.R
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import java.util.Locale
/**
 * Champ téléphone intelligent (hors-ligne) :
 *  - sélecteur de pays cherchable (drapeau + indicatif) ;
 *  - si l'utilisateur tape le numéro international (+…), l'indicatif est détecté
 *    et SÉPARÉ automatiquement (le pays bascule, le champ garde le national) ;
 *  - validation par pays + message d'erreur si non conforme ;
 *  - aide au format (exemple de numéro du pays) sous le champ.
 * L'appelant gère l'état (region, rawNumber) et lit l'E.164 via PhoneTools.parse.
 */
@Composable
fun PhoneField(
    label: String,
    util: PhoneNumberUtil,
    region: String,
    onRegionChange: (String) -> Unit,
    rawNumber: String,
    onRawNumberChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val locale = remember { context.resources.configuration.locales[0] }
    var showPicker by remember { mutableStateOf(false) }
    val parse = remember(region, rawNumber) { PhoneTools.parse(util, region, rawNumber) }
    val example = remember(region) { PhoneTools.example(util, region) }
    val code = remember(region) { util.getCountryCodeForRegion(region) }
    val flag = remember(region) { PhoneTools.flag(region) }
    val countryCd = stringResource(R.string.tools_phone_country_cd)
    val invalid = rawNumber.isNotBlank() && !parse.valid
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { showPicker = true },
                modifier = Modifier
                    .height(56.dp)
                    .semantics { contentDescription = "$countryCd, +$code" },
            ) {
                Text("$flag +$code")
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = rawNumber,
                onValueChange = { input ->
                    // Séparation auto de l'indicatif si numéro international collé/saisi.
                    val split = PhoneTools.splitInternational(util, input)
                    if (split != null) {
                        onRegionChange(split.first)
                        onRawNumberChange(split.second)
                    } else {
                        onRawNumberChange(input)
                    }
                },
                label = { Text(label) },
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f),
            )
        }
        // Aide au format / erreur SOUS la ligne (pleine largeur) : le sélecteur de
        // pays et le champ (tous deux 56 dp) restent ainsi centrés et alignés.
        val support = when {
            invalid -> stringResource(R.string.tools_phone_invalid)
            example != null -> stringResource(R.string.tools_phone_format, example)
            else -> null
        }
        if (support != null) {
            Text(
                support,
                style = MaterialTheme.typography.bodySmall,
                color = if (invalid) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
    }
    if (showPicker) {
        CountryPickerDialog(
            util = util,
            locale = locale,
            onDismiss = { showPicker = false },
            onSelect = {
                onRegionChange(it)
                showPicker = false
            },
        )
    }
}
/** Dialogue de sélection de pays avec recherche (nom, indicatif ou code ISO). */
@Composable
private fun CountryPickerDialog(
    util: PhoneNumberUtil,
    locale: Locale,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val all = remember { PhoneTools.countries(util, locale) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) {
            all
        } else {
            all.filter {
                it.name.contains(query, ignoreCase = true) ||
                    "+${it.code}".contains(query) ||
                    it.region.contains(query, ignoreCase = true)
            }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.tools_country_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.tools_country_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(filtered, key = { it.region }) { c ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(c.region) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(c.flag)
                                Spacer(Modifier.width(12.dp))
                                Text(c.name, modifier = Modifier.weight(1f))
                                Text("+${c.code}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
