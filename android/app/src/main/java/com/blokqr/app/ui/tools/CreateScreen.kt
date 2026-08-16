package com.blokqr.app.ui.tools
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.data.CreateHistoryStore
import com.blokqr.app.data.CreatedCode
import com.blokqr.app.util.BitmapDecoding
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import kotlinx.coroutines.launch
/** Types de code générables. Tous produisent un QR (M3 ajoutera le multi-format). */
private enum class CodeType(val icon: ImageVector, val labelRes: Int) {
    // --- Types simples (M1) ---
    URL(Icons.Rounded.Link, R.string.tools_type_url),
    TEXT(Icons.Rounded.TextFields, R.string.tools_type_text),
    CLIPBOARD(Icons.Rounded.ContentPaste, R.string.tools_type_clipboard),
    PHONE(Icons.Rounded.Call, R.string.tools_type_phone),
    SMS(Icons.Rounded.Sms, R.string.tools_type_sms),
    EMAIL(Icons.Rounded.Email, R.string.tools_type_email),
    APP(Icons.Rounded.Apps, R.string.tools_type_app),
    WHATSAPP(Icons.AutoMirrored.Rounded.Chat, R.string.tools_type_whatsapp),
    // --- Types riches (M2a) ---
    CONTACT(Icons.Rounded.ContactPage, R.string.tools_type_contact),
    WIFI(Icons.Rounded.Wifi, R.string.tools_type_wifi),
    GEO(Icons.Rounded.LocationOn, R.string.tools_type_geo),
    EVENT(Icons.Rounded.Event, R.string.tools_type_event),
}
/** Type de sécurité Wi-Fi (mappé sur la convention WIFI: T:WPA|WEP|nopass). */
private enum class WifiSecurity(val labelRes: Int, val tag: String) {
    WPA(R.string.tools_sec_wpa, "WPA"),
    WEP(R.string.tools_sec_wep, "WEP"),
    OPEN(R.string.tools_sec_open, "nopass"),
}
/** État visuel de la zone d'aperçu (pour le fondu Crossfade). */
private enum class PreviewState { CODE, INCOMPATIBLE, EMPTY }
/** Source du logo affiché au centre d'un QR : aucun, logo BlokQR, ou logo importé. */
private enum class LogoSource { NONE, BLOKQR, CUSTOM }
/**
 * Onglet « Créer » — générateur de QR codes.
 * Aperçu recalculé en direct ; partage (galerie + feuille système) et impression
 * via CodeExport. 100 % hors-ligne : le code est encodé sur l'appareil.
 *
 * Personnalisation couleur : pour les codes 2D, l'utilisateur choisit la couleur
 * des modules et du fond (palettes sûres + nuancier libre). Un garde-fou de
 * contraste (WCAG) prévient et bloque l'export d'un code non scannable. Les
 * codes 1D restent noir sur blanc (contrainte des lecteurs linéaires).
 *
 * Logo au centre (QR uniquement) : aucun, le logo BlokQR, ou un logo importé
 * depuis la galerie (sélecteur de photo sans permission, décodage sous-
 * échantillonné, recadrage carré). Un logo présent force la correction
 * d'erreur H afin de garder le code parfaitement scannable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val createHistory = remember { CreateHistoryStore(context) }
    val util = remember { PhoneTools.createUtil(context) }
    val defaultRegion = remember {
        context.resources.configuration.locales[0].country.ifBlank { "US" }
    }
    var type by remember { mutableStateOf(CodeType.URL) }
    var format by remember { mutableStateOf(CodeFormat.QR) }
    var showCreateHistory by remember { mutableStateOf(false) }
    // --- Couleurs du code (modules + fond). Appliquées aux codes 2D seulement. ---
    var fgColor by remember { mutableStateOf(Color(0xFF000000)) }
    var bgColor by remember { mutableStateOf(Color(0xFFFFFFFF)) }
    // --- Logo au centre (QR uniquement) : aucun / logo BlokQR / logo importé. ---
    var logoSource by remember { mutableStateOf(LogoSource.NONE) }
    var customLogoUri by remember { mutableStateOf<Uri?>(null) }
    // Sélecteur de photo moderne (Photo Picker) : aucune permission requise.
    val logoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) { customLogoUri = uri; logoSource = LogoSource.CUSTOM } }
    // --- États des champs (types simples) ---
    var url by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var phoneRegion by remember { mutableStateOf(defaultRegion) }
    var phoneRaw by remember { mutableStateOf("") }
    var smsRegion by remember { mutableStateOf(defaultRegion) }
    var smsRaw by remember { mutableStateOf("") }
    var smsMessage by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var emailSubject by remember { mutableStateOf("") }
    var emailBody by remember { mutableStateOf("") }
    var appPackage by remember { mutableStateOf("") }
    var waRegion by remember { mutableStateOf(defaultRegion) }
    var waRaw by remember { mutableStateOf("") }
    var waMessage by remember { mutableStateOf("") }
    // --- États des champs (Contact / vCard) ---
    var cFirstName by remember { mutableStateOf("") }
    var cLastName by remember { mutableStateOf("") }
    var cCompany by remember { mutableStateOf("") }
    var cJobTitle by remember { mutableStateOf("") }
    var cPhoneRegion by remember { mutableStateOf(defaultRegion) }
    var cPhoneRaw by remember { mutableStateOf("") }
    var cEmail by remember { mutableStateOf("") }
    var cAddress by remember { mutableStateOf("") }
    var cPostal by remember { mutableStateOf("") }
    var cCity by remember { mutableStateOf("") }
    var cRegion by remember { mutableStateOf("") }
    var cCountry by remember { mutableStateOf("") }
    var cWebsite by remember { mutableStateOf("") }
    // --- États des champs (Wi-Fi) ---
    var ssid by remember { mutableStateOf("") }
    var wifiSec by remember { mutableStateOf(WifiSecurity.WPA) }
    var wifiPassword by remember { mutableStateOf("") }
    var wifiHidden by remember { mutableStateOf(false) }
    // --- États des champs (Géolocalisation) ---
    var geoLat by remember { mutableStateOf("") }
    var geoLng by remember { mutableStateOf("") }
    // --- États des champs (Événement) ---
    var evTitle by remember { mutableStateOf("") }
    var evAllDay by remember { mutableStateOf(false) }
    var evStartDate by remember { mutableStateOf("") }
    var evStartTime by remember { mutableStateOf("") }
    var evEndDate by remember { mutableStateOf("") }
    var evEndTime by remember { mutableStateOf("") }
    var evLocation by remember { mutableStateOf("") }
    var evDescription by remember { mutableStateOf("") }
    // Numéros validés en E.164 (hors-ligne) pour les champs téléphone.
    val phoneE164 = remember(phoneRegion, phoneRaw) { PhoneTools.parse(util, phoneRegion, phoneRaw).e164 }
    val smsE164 = remember(smsRegion, smsRaw) { PhoneTools.parse(util, smsRegion, smsRaw).e164 }
    val waE164 = remember(waRegion, waRaw) { PhoneTools.parse(util, waRegion, waRaw).e164 }
    val cPhoneE164 = remember(cPhoneRegion, cPhoneRaw) { PhoneTools.parse(util, cPhoneRegion, cPhoneRaw).e164 }
    // Charge utile encodée, recalculée à chaque changement pertinent.
    val payload = remember(
        type, url, text, phoneE164, smsE164, smsMessage, email, emailSubject, emailBody,
        appPackage, waE164, waMessage,
        cFirstName, cLastName, cCompany, cJobTitle, cPhoneE164, cEmail, cAddress, cPostal,
        cCity, cRegion, cCountry, cWebsite,
        ssid, wifiSec, wifiPassword, wifiHidden,
        geoLat, geoLng,
        evTitle, evAllDay, evStartDate, evStartTime, evEndDate, evEndTime, evLocation, evDescription,
    ) {
        when (type) {
            CodeType.CONTACT -> CodePayloads.vCard(
                cFirstName, cLastName, cCompany, cJobTitle, cPhoneE164, cEmail,
                cAddress, cPostal, cCity, cRegion, cCountry, cWebsite,
            )
            CodeType.WIFI -> CodePayloads.wifi(ssid, wifiSec.tag, wifiPassword, wifiHidden)
            CodeType.GEO -> CodePayloads.geo(geoLat, geoLng)
            CodeType.EVENT -> CodePayloads.vEvent(
                evTitle, evAllDay, evStartDate, evStartTime,
                evEndDate, evEndTime, evLocation, evDescription,
            )
            else -> buildSimplePayload(
                type, url, text, phoneE164, smsE164, smsMessage,
                email, emailSubject, emailBody, appPackage, waE164, waMessage,
            )
        }
    }
    // Couleurs converties en ARGB pour l'encodeur.
    val fgArgb = fgColor.toArgb()
    val bgArgb = bgColor.toArgb()
    // Couleur active seulement sur les codes 2D et si l'utilisateur a quitté le
    // noir/blanc par défaut.
    val colored = format.matrix &&
        !(fgArgb == android.graphics.Color.BLACK && bgArgb == android.graphics.Color.WHITE)
    val scanSafe = !colored || CodeEncoder.isScanSafe(fgArgb, bgArgb)
    val contrast = CodeEncoder.contrastRatio(fgArgb, bgArgb)
    // Logo au centre : décodé selon la source choisie, toujours sous-échantillonné
    // (BitmapDecoding). Le logo importé est recadré carré. Appliqué au QR seulement.
    val logoBitmap = remember(logoSource, customLogoUri) {
        when (logoSource) {
            LogoSource.NONE -> null
            LogoSource.BLOKQR ->
                BitmapDecoding.decodeResource(context.resources, R.drawable.blokqr_logo_mark, 256, 256)
            LogoSource.CUSTOM ->
                customLogoUri?.let { uri ->
                    BitmapDecoding.decodeUri(context.contentResolver, uri, 256, 256)?.let { squareCrop(it) }
                }
        }
    }
    val effLogo = if (format == CodeFormat.QR) logoBitmap else null
    val bitmap = remember(payload, format, fgArgb, bgArgb, effLogo) {
        payload?.let {
            CodeEncoder.encodeOrNull(
                it, format, if (format.matrix) 600 else 800,
                fgColor = fgArgb, bgColor = bgArgb, logo = effLogo,
            )
        }
    }
    // Overlay : historique des créations. Les états de formulaire (déclarés plus
    // haut) sont préservés au retour, car ils sont composés AVANT ce return.
    if (showCreateHistory) {
        CreateHistoryScreen(onClose = { showCreateHistory = false })
        return
    }
    // Enregistre le code courant dans l'historique. Libellé SANS secret : pour le
    // Wi-Fi on garde le SSID (jamais le mot de passe). Appelé au partage/impression.
    val saveToHistory = {
        val p = payload
        if (p != null) {
            val detail = when (type) {
                CodeType.WIFI -> ssid
                CodeType.CONTACT ->
                    listOf(cFirstName, cLastName).filter { it.isNotBlank() }
                        .joinToString(" ").ifBlank { cCompany }
                CodeType.EVENT -> evTitle
                else -> p
            }.trim()
            val typeName = context.getString(type.labelRes)
            val label = if (detail.isBlank()) typeName else "$typeName · $detail"
            scope.launch {
                createHistory.save(
                    CreatedCode(
                        label = label,
                        payload = p,
                        format = format.name,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.tab_create),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showCreateHistory = true }) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = stringResource(R.string.create_history_title),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // Sélecteur de type : FlowRow -> toutes les puces sont visibles (elles
        // passent à la ligne). L'icône rassure sur le choix.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CodeType.values().forEach { t ->
                FilterChip(
                    selected = type == t,
                    onClick = { type = t; format = CodeFormat.QR },
                    label = { Text(stringResource(t.labelRes)) },
                    leadingIcon = {
                        Icon(t.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // Champs spécifiques au type sélectionné.
        when (type) {
            CodeType.URL -> Field(url, { url = it }, stringResource(R.string.tools_field_url), KeyboardType.Uri)
            CodeType.TEXT -> Field(text, { text = it }, stringResource(R.string.tools_field_text), multiline = true)
            CodeType.CLIPBOARD -> {
                Field(text, { text = it }, stringResource(R.string.tools_field_text), multiline = true)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { clipboard.getText()?.let { text = it.text } }) {
                    Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tools_action_paste))
                }
            }
            CodeType.PHONE -> PhoneField(
                stringResource(R.string.tools_field_phone), util,
                phoneRegion, { phoneRegion = it }, phoneRaw, { phoneRaw = it },
            )
            CodeType.SMS -> {
                PhoneField(
                    stringResource(R.string.tools_field_number), util,
                    smsRegion, { smsRegion = it }, smsRaw, { smsRaw = it },
                )
                Spacer(Modifier.height(8.dp))
                Field(smsMessage, { smsMessage = it }, stringResource(R.string.tools_field_message), multiline = true)
            }
            CodeType.EMAIL -> {
                Field(email, { email = it }, stringResource(R.string.tools_field_email), KeyboardType.Email)
                Spacer(Modifier.height(8.dp))
                Field(emailSubject, { emailSubject = it }, stringResource(R.string.tools_field_subject))
                Spacer(Modifier.height(8.dp))
                Field(emailBody, { emailBody = it }, stringResource(R.string.tools_field_message), multiline = true)
            }
            CodeType.APP -> Field(appPackage, { appPackage = it }, stringResource(R.string.tools_field_package))
            CodeType.WHATSAPP -> {
                PhoneField(
                    stringResource(R.string.tools_field_number), util,
                    waRegion, { waRegion = it }, waRaw, { waRaw = it },
                )
                Spacer(Modifier.height(8.dp))
                Field(waMessage, { waMessage = it }, stringResource(R.string.tools_field_message), multiline = true)
            }
            CodeType.CONTACT -> ContactForm(
                util,
                cFirstName, { cFirstName = it }, cLastName, { cLastName = it },
                cCompany, { cCompany = it }, cJobTitle, { cJobTitle = it },
                cPhoneRegion, { cPhoneRegion = it }, cPhoneRaw, { cPhoneRaw = it },
                cEmail, { cEmail = it },
                cAddress, { cAddress = it }, cPostal, { cPostal = it },
                cCity, { cCity = it }, cRegion, { cRegion = it },
                cCountry, { cCountry = it }, cWebsite, { cWebsite = it },
            )
            CodeType.WIFI -> WifiForm(
                ssid, { ssid = it }, wifiSec, { wifiSec = it },
                wifiPassword, { wifiPassword = it }, wifiHidden, { wifiHidden = it },
            )
            CodeType.GEO -> GeoForm(geoLat, { geoLat = it }, geoLng, { geoLng = it })
            CodeType.EVENT -> EventForm(
                evTitle, { evTitle = it }, evAllDay, { evAllDay = it },
                evStartDate, { evStartDate = it }, evStartTime, { evStartTime = it },
                evEndDate, { evEndDate = it }, evEndTime, { evEndTime = it },
                evLocation, { evLocation = it }, evDescription, { evDescription = it },
            )
        }
        Spacer(Modifier.height(20.dp))
        // Sélecteur de format. Les codes 1D exigent un contenu compatible.
        Text(
            stringResource(R.string.tools_format_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CodeFormat.values().forEach { f ->
                FilterChip(
                    selected = format == f,
                    onClick = { format = f },
                    label = { Text(formatLabel(f)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
        // Personnalisation couleur — codes 2D uniquement (QR, Data Matrix, Aztec).
        if (format.matrix) {
            Spacer(Modifier.height(20.dp))
            ColorCustomizer(
                fg = fgColor, bg = bgColor,
                onFg = { fgColor = it }, onBg = { bgColor = it },
                contrast = contrast, safe = scanSafe,
            )
        }
        // Logo au centre — QR uniquement (correction d'erreur H forcée).
        if (format == CodeFormat.QR) {
            Spacer(Modifier.height(12.dp))
            LogoChooser(
                source = logoSource,
                hasCustom = customLogoUri != null,
                onNone = { logoSource = LogoSource.NONE },
                onBlokqr = { logoSource = LogoSource.BLOKQR },
                onCustom = {
                    if (customLogoUri != null) logoSource = LogoSource.CUSTOM
                    else logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onPick = {
                    logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
        Spacer(Modifier.height(20.dp))
        // Aperçu à fondu doux : apparition élégante quand un code devient
        // disponible, sans re-animer à chaque frappe (transition d'ÉTAT seulement).
        val previewState = when {
            bitmap != null -> PreviewState.CODE
            payload != null -> PreviewState.INCOMPATIBLE
            else -> PreviewState.EMPTY
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Crossfade(
                targetState = previewState,
                animationSpec = tween(220),
                label = "codePreview",
            ) { st ->
                when (st) {
                    PreviewState.CODE ->
                        CodePreview(bitmap = bitmap, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth())
                    PreviewState.INCOMPATIBLE ->
                        Text(
                            // Contenu non encodable dans le format choisi (lettres en EAN...).
                            stringResource(R.string.tools_format_incompatible),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp),
                        )
                    PreviewState.EMPTY ->
                        CodePreview(bitmap = null, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth())
                }
            }
        }
        if (bitmap != null) {
            Spacer(Modifier.height(20.dp))
            if (!scanSafe) {
                // Contraste insuffisant : on affiche l'alerte et on bloque l'export
                // d'un code potentiellement illisible par les scanners.
                Text(
                    stringResource(R.string.tools_colors_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            } else {
                com.blokqr.app.ui.theme.PrimaryButton(
                    text = stringResource(R.string.result_action_share),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        saveToHistory()
                        scope.launch { CodeExport.share(context, bitmap) }
                    },
                    icon = Icons.Rounded.Share,
                    enabled = true,
                )
                Spacer(Modifier.height(8.dp))
                com.blokqr.app.ui.theme.SecondaryButton(
                    text = stringResource(R.string.tools_action_print),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        saveToHistory()
                        CodeExport.print(context, bitmap)
                    },
                    icon = Icons.Rounded.Print,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
/* --------------------------- Formulaires riches -------------------------- */
@Composable
private fun ContactForm(
    util: PhoneNumberUtil,
    firstName: String, onFirstName: (String) -> Unit,
    lastName: String, onLastName: (String) -> Unit,
    company: String, onCompany: (String) -> Unit,
    jobTitle: String, onJobTitle: (String) -> Unit,
    phoneRegion: String, onPhoneRegion: (String) -> Unit,
    phoneRaw: String, onPhoneRaw: (String) -> Unit,
    email: String, onEmail: (String) -> Unit,
    address: String, onAddress: (String) -> Unit,
    postal: String, onPostal: (String) -> Unit,
    city: String, onCity: (String) -> Unit,
    region: String, onRegion: (String) -> Unit,
    country: String, onCountry: (String) -> Unit,
    website: String, onWebsite: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(firstName, onFirstName, stringResource(R.string.tools_field_firstname))
        Field(lastName, onLastName, stringResource(R.string.tools_field_lastname))
        Field(company, onCompany, stringResource(R.string.tools_field_company))
        Field(jobTitle, onJobTitle, stringResource(R.string.tools_field_jobtitle))
        PhoneField(stringResource(R.string.tools_field_phone), util, phoneRegion, onPhoneRegion, phoneRaw, onPhoneRaw)
        Field(email, onEmail, stringResource(R.string.tools_field_email), KeyboardType.Email)
        Field(address, onAddress, stringResource(R.string.tools_field_address))
        Field(postal, onPostal, stringResource(R.string.tools_field_postalcode))
        Field(city, onCity, stringResource(R.string.tools_field_city))
        Field(region, onRegion, stringResource(R.string.tools_field_region))
        Field(country, onCountry, stringResource(R.string.tools_field_country))
        Field(website, onWebsite, stringResource(R.string.tools_field_website), KeyboardType.Uri)
    }
}
@Composable
private fun WifiForm(
    ssid: String, onSsid: (String) -> Unit,
    security: WifiSecurity, onSecurity: (WifiSecurity) -> Unit,
    password: String, onPassword: (String) -> Unit,
    hidden: Boolean, onHidden: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(ssid, onSsid, stringResource(R.string.tools_field_ssid))
        Text(
            stringResource(R.string.tools_field_security),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WifiSecurity.values().forEach { s ->
                FilterChip(
                    selected = security == s,
                    onClick = { onSecurity(s) },
                    label = { Text(stringResource(s.labelRes)) },
                )
            }
        }
        if (security != WifiSecurity.OPEN) {
            Field(password, onPassword, stringResource(R.string.tools_field_password))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.tools_wifi_hidden),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = hidden, onCheckedChange = onHidden)
        }
    }
}
@SuppressLint("MissingPermission")
@Composable
private fun GeoForm(
    lat: String, onLat: (String) -> Unit,
    lng: String, onLng: (String) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(false) }
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    // Récupère un point GPS et remplit lat/lng. La permission est vérifiée en
    // amont ; les coordonnées restent sur l'appareil (aucune requête tierce).
    fun fetch() {
        loading = true
        try {
            fused.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            ).addOnSuccessListener { loc ->
                loading = false
                if (loc != null) {
                    onLat(formatCoord(loc.latitude))
                    onLng(formatCoord(loc.longitude))
                } else {
                    toastRes(context, R.string.tools_location_unavailable)
                }
            }.addOnFailureListener {
                loading = false
                toastRes(context, R.string.tools_location_unavailable)
            }
        } catch (e: SecurityException) {
            loading = false
        }
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) fetch() else toastRes(context, R.string.tools_location_denied)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(lat, onLat, stringResource(R.string.tools_field_latitude), KeyboardType.Number)
        Field(lng, onLng, stringResource(R.string.tools_field_longitude), KeyboardType.Number)
        OutlinedButton(
            enabled = !loading,
            onClick = {
                if (hasLocationPermission(context)) fetch()
                else permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            },
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tools_use_current_location))
        }
    }
}
@Composable
private fun EventForm(
    title: String, onTitle: (String) -> Unit,
    allDay: Boolean, onAllDay: (Boolean) -> Unit,
    startDate: String, onStartDate: (String) -> Unit,
    startTime: String, onStartTime: (String) -> Unit,
    endDate: String, onEndDate: (String) -> Unit,
    endTime: String, onEndTime: (String) -> Unit,
    location: String, onLocation: (String) -> Unit,
    description: String, onDescription: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(title, onTitle, stringResource(R.string.tools_field_event_title))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.tools_event_allday),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = allDay, onCheckedChange = onAllDay)
        }
        Field(
            startDate, onStartDate, stringResource(R.string.tools_field_startdate),
            KeyboardType.Number, supporting = stringResource(R.string.tools_hint_date),
        )
        if (!allDay) {
            Field(
                startTime, onStartTime, stringResource(R.string.tools_field_starttime),
                KeyboardType.Number, supporting = stringResource(R.string.tools_hint_time),
            )
        }
        Field(
            endDate, onEndDate, stringResource(R.string.tools_field_enddate),
            KeyboardType.Number, supporting = stringResource(R.string.tools_hint_date),
        )
        if (!allDay) {
            Field(
                endTime, onEndTime, stringResource(R.string.tools_field_endtime),
                KeyboardType.Number, supporting = stringResource(R.string.tools_hint_time),
            )
        }
        Field(location, onLocation, stringResource(R.string.tools_field_location))
        Field(description, onDescription, stringResource(R.string.tools_field_description), multiline = true)
    }
}
/* ------------------------------ Logo au centre --------------------------- */
@Composable
private fun LogoChooser(
    source: LogoSource,
    hasCustom: Boolean,
    onNone: () -> Unit,
    onBlokqr: () -> Unit,
    onCustom: () -> Unit,
    onPick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.tools_logo_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = source == LogoSource.NONE,
                onClick = onNone,
                label = { Text(stringResource(R.string.tools_logo_none)) },
            )
            FilterChip(
                selected = source == LogoSource.BLOKQR,
                onClick = onBlokqr,
                label = { Text(stringResource(R.string.tools_logo_blokqr)) },
            )
            FilterChip(
                selected = source == LogoSource.CUSTOM,
                onClick = onCustom,
                label = { Text(stringResource(R.string.tools_logo_custom)) },
            )
        }
        if (source == LogoSource.CUSTOM) {
            OutlinedButton(onClick = onPick) {
                Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (hasCustom) R.string.tools_logo_change else R.string.tools_logo_pick))
            }
        }
    }
}
/** Recadre un bitmap en carré centré (logo importé net au centre du QR). */
private fun squareCrop(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val s = minOf(src.width, src.height)
    return android.graphics.Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
}
/* ----------------------------- Champ réutilisable ------------------------ */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        supportingText = supporting?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}
/* ------------------------------ Couleurs du code ------------------------- */
// Paires (modules, fond) prêtes à l'emploi, toutes validées lisibles au scan.
private val COLOR_PALETTES: List<Pair<Color, Color>> = listOf(
    Color(0xFF000000) to Color(0xFFFFFFFF), // Classique
    Color(0xFF0A111F) to Color(0xFFFFFFFF), // Bleu nuit (charte)
    Color(0xFF003399) to Color(0xFFFFFFFF), // Bleu roi (charte)
    Color(0xFF0B5D2E) to Color(0xFFFFFFFF), // Vert forêt
    Color(0xFF3B1E6E) to Color(0xFFFFFFFF), // Violet
    Color(0xFF6E1020) to Color(0xFFFFFFFF), // Bordeaux
    Color(0xFF14202E) to Color(0xFFEAF0F6), // Ardoise sur gris clair
    Color(0xFF0A3D3A) to Color(0xFFE6FBFA), // Sarcelle sur menthe
)
// Nuancier libre : l'utilisateur compose sa paire ; le garde-fou de contraste
// prévient et bloque l'export si le résultat n'est pas scannable.
private val COLOR_SWATCHES: List<Color> = listOf(
    Color(0xFF000000), Color(0xFF0A111F), Color(0xFF003399), Color(0xFF0B5D2E),
    Color(0xFF3B1E6E), Color(0xFF6E1020), Color(0xFF14202E), Color(0xFF5A4B00),
    Color(0xFF444444), Color(0xFF8A0F0F), Color(0xFFB8C4D0), Color(0xFFEAF0F6),
    Color(0xFFFFFFFF),
)
@Composable
private fun ColorCustomizer(
    fg: Color, bg: Color,
    onFg: (Color) -> Unit, onBg: (Color) -> Unit,
    contrast: Double, safe: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.tools_colors_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Palettes rapides (paires sûres).
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            COLOR_PALETTES.forEach { (pf, pb) ->
                PalettePair(
                    fg = pf, bg = pb,
                    selected = pf == fg && pb == bg,
                    onClick = { onFg(pf); onBg(pb) },
                )
            }
        }
        // Choix libre : modules.
        Text(
            stringResource(R.string.tools_colors_modules),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            COLOR_SWATCHES.forEach { c ->
                Swatch(color = c, selected = c == fg, onClick = { onFg(c) })
            }
        }
        // Choix libre : fond.
        Text(
            stringResource(R.string.tools_colors_background),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            COLOR_SWATCHES.forEach { c ->
                Swatch(color = c, selected = c == bg, onClick = { onBg(c) })
            }
        }
        // Indicateur de contraste en direct.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (safe) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                contentDescription = null,
                tint = if (safe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (safe) R.string.tools_colors_contrast_ok else R.string.tools_colors_contrast_low,
                    String.format(Locale.US, "%.1f", contrast),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (safe) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}
@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}
@Composable
private fun PalettePair(fg: Color, bg: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(fg),
        )
    }
}
/** Nom d'affichage d'un format (identifiants standard, non traduits). */
private fun formatLabel(f: CodeFormat): String = when (f) {
    CodeFormat.QR -> "QR code"
    CodeFormat.DATA_MATRIX -> "Data Matrix"
    CodeFormat.AZTEC -> "Aztec"
    CodeFormat.PDF417 -> "PDF417"
    CodeFormat.CODE_128 -> "Code 128"
    CodeFormat.CODE_39 -> "Code 39"
    CodeFormat.CODE_93 -> "Code 93"
    CodeFormat.CODABAR -> "Codabar"
    CodeFormat.ITF -> "ITF"
    CodeFormat.EAN_13 -> "EAN-13"
    CodeFormat.EAN_8 -> "EAN-8"
    CodeFormat.UPC_A -> "UPC-A"
    CodeFormat.UPC_E -> "UPC-E"
}
/* ----------------------------- Constructeurs ----------------------------- */
/** Types simples (M1) — inchangé. */
private fun buildSimplePayload(
    type: CodeType,
    url: String, text: String, phoneE164: String?, smsE164: String?, smsMessage: String,
    email: String, emailSubject: String, emailBody: String,
    appPackage: String, waE164: String?, waMessage: String,
): String? = when (type) {
    CodeType.URL ->
        url.trim().ifBlank { null }?.let { if (it.contains("://")) it else "https://$it" }
    CodeType.TEXT, CodeType.CLIPBOARD -> text.ifBlank { null }
    CodeType.PHONE -> phoneE164?.let { "tel:$it" }
    CodeType.SMS -> smsE164?.let { "SMSTO:$it:${smsMessage.trim()}" }
    CodeType.EMAIL -> email.trim().ifBlank { null }?.let { buildMailto(it, emailSubject.trim(), emailBody.trim()) }
    CodeType.APP -> appPackage.trim().ifBlank { null }
        ?.let { "https://play.google.com/store/apps/details?id=$it" }
    CodeType.WHATSAPP -> waE164?.let { e164 ->
        val digits = e164.filter { it.isDigit() }
        val base = "https://wa.me/$digits"
        if (waMessage.isNotBlank()) "$base?text=${Uri.encode(waMessage)}" else base
    }
    else -> null
}
private fun buildMailto(address: String, subject: String, body: String): String {
    val params = buildList {
        if (subject.isNotBlank()) add("subject=${Uri.encode(subject)}")
        if (body.isNotBlank()) add("body=${Uri.encode(body)}")
    }
    return if (params.isEmpty()) "mailto:$address" else "mailto:$address?${params.joinToString("&")}"
}
/* ----------------------------- Localisation ------------------------------ */
private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
private fun formatCoord(value: Double): String = String.format(Locale.US, "%.6f", value)
private fun toastRes(context: Context, res: Int) {
    Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
}