package com.blokqr.app.ui.url

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.scanner.OcrUrlRecognizer
import com.blokqr.app.ui.theme.PrimaryButton
import com.blokqr.app.ui.theme.ScreenContainer
import com.blokqr.app.ui.theme.SecondaryButton
import com.blokqr.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Écran « Analyser une URL » : lecture d'une URL par OCR 100 % ON-DEVICE
 * (ML Kit), pour les URLs imprimées/affichées qui ne sont pas des QR codes.
 *
 * Flux : photo (caméra) ou image (galerie) -> OcrUrlRecognizer -> champ
 * ÉDITABLE pré-rempli -> bouton « Analyser » qui réinjecte l'URL dans le
 * pipeline standard (même /v1/analyze, verdict signé). Aucune image ni texte
 * ne quitte l'appareil pendant l'OCR.
 */
@Composable
fun AnalyzeUrlScreen(
    onAnalyze: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Lance une reconnaissance OCR et pré-remplit le champ (ou affiche un message).
    fun runOcr(recognize: suspend () -> String?) {
        scope.launch {
            loading = true
            message = null
            try {
                val found = recognize()
                if (found != null) {
                    url = found
                } else {
                    message = context.getString(R.string.url_none_found)
                }
            } catch (e: Exception) {
                message = context.getString(R.string.url_ocr_error)
            } finally {
                loading = false
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) runOcr { OcrUrlRecognizer.recognizeUrl(bitmap) }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) runOcr { OcrUrlRecognizer.recognizeUrl(context, uri) }
    }

    ScreenContainer(horizontalAlignment = Alignment.Start) {

        // En-tête avec retour.
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
                stringResource(R.string.url_analyze_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }

        Spacer(Modifier.height(Spacing.md))
        Text(
            stringResource(R.string.url_analyze_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.lg))
        PrimaryButton(
            text = stringResource(R.string.url_take_photo),
            onClick = { cameraLauncher.launch(null) },
            icon = Icons.Rounded.PhotoCamera,
            enabled = !loading,
        )
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton(
            text = stringResource(R.string.url_from_gallery),
            onClick = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            icon = Icons.Rounded.Photo,
        )

        Spacer(Modifier.height(Spacing.lg))

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    stringResource(R.string.url_reading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.md))
        }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(Spacing.sm))
        }

        // Champ ÉDITABLE : l'utilisateur peut corriger l'URL lue avant analyse.
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; message = null },
            label = { Text(stringResource(R.string.url_field_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = { if (url.isNotBlank()) onAnalyze(url.trim()) },
            ),
        )

        Spacer(Modifier.height(Spacing.lg))
        PrimaryButton(
            text = stringResource(R.string.url_analyze_cta),
            onClick = { onAnalyze(url.trim()) },
            icon = Icons.Rounded.Shield,
            enabled = url.isNotBlank() && !loading,
        )
    }
}