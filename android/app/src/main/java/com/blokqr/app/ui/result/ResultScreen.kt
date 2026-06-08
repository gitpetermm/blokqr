package com.blokqr.app.ui.result

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blokqr.app.ai.PhishingClassifier
import com.blokqr.app.model.OpeningPolicy
import com.blokqr.app.model.Verdict
import com.blokqr.app.model.VerifiedResult
import com.blokqr.app.ui.theme.NeutralGrey

/**
 * Écran plein de résultat.
 *
 *  - Couleur dominante = couleur du verdict.
 *  - Contenu non navigable (texte, vCard…) : affichage neutre, sans évaluation.
 *  - Actions conditionnées au palier (Safe / Dangerous / Malicious).
 *  - Double prévisualisation : capture de la page finale + raisons en langage clair.
 */
@Composable
fun ResultScreen(
    result: VerifiedResult,
    aiAssessment: PhishingClassifier.Assessment?,
    onOpenSandbox: (String) -> Unit,
    onReport: () -> Unit,
    onRescan: () -> Unit,
) {
    val verdict = if (!result.isNavigable) Verdict.NEUTRAL else result.verdict
    val accent = verdict.color
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .background(accent.copy(alpha = 0.12f))
            .verticalScroll(scroll)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // --- Bandeau de verdict ------------------------------------------------
        Surface(
            color = accent, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${verdict.emoji}  ${verdict.label}", color = Color.White,
                    fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(headlineFor(verdict, result), color = Color.White.copy(alpha = 0.95f),
                    fontSize = 15.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!result.isNavigable) {
            NeutralContent(result)
        } else {
            DestinationCard(result)
            Spacer(Modifier.height(12.dp))
            DoublePreview(result, aiAssessment)
            Spacer(Modifier.height(12.dp))
            ReasonsCard(result)
            Spacer(Modifier.height(16.dp))
            Actions(verdict, result, onOpenSandbox, onReport)
        }

        Spacer(Modifier.height(20.dp))
        SignatureFooter(result)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRescan) { Text("Scanner un autre code") }
    }
}

private fun headlineFor(v: Verdict, r: VerifiedResult): String = when (v) {
    Verdict.SAFE -> "Aucune menace détectée."
    Verdict.DANGEROUS -> "Lien suspect — ouverture restreinte."
    Verdict.MALICIOUS -> "Menace confirmée — lien bloqué."
    Verdict.UNKNOWN -> "Analyse incomplète — vérification impossible."
    Verdict.NEUTRAL -> "Contenu informatif (non navigable)."
}

@Composable
private fun NeutralContent(result: VerifiedResult) {
    Surface(color = NeutralGrey.copy(alpha = 0.18f), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Contenu décodé", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(result.report.displayedValue, fontSize = 14.sp)
        }
    }
}

@Composable
private fun DestinationCard(result: VerifiedResult) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Destination finale", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(result.report.finalTarget ?: result.report.displayedValue, fontSize = 14.sp)
            val hops = result.report.redirectChain.size
            if (hops > 1) {
                Spacer(Modifier.height(6.dp))
                Text("$hops sauts de redirection suivis", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            if (result.report.destinationChanged) {
                Spacer(Modifier.height(6.dp))
                Text("⚠️ La destination a changé depuis le dernier scan de ce code.",
                    fontSize = 12.sp, color = Verdict.DANGEROUS.color)
            }
        }
    }
}

@Composable
private fun DoublePreview(result: VerifiedResult, ai: PhishingClassifier.Assessment?) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Aperçu de la page finale", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            val b64 = result.report.screenshotB64
            if (!b64.isNullOrEmpty()) {
                val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Capture de la page finale",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                    )
                }
            } else {
                Text("Aperçu indisponible (page non rendue).", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            // Évaluation IA embarquée (sur l'appareil).
            if (ai != null) {
                Spacer(Modifier.height(8.dp))
                Text("Analyse visuelle (sur l'appareil) : ${ai.label} " +
                    "(${(ai.impersonationProbability * 100).toInt()} %)",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            result.report.loginImpersonation?.let {
                Spacer(Modifier.height(4.dp))
                Text("Cette page se fait passer pour « $it ».",
                    fontSize = 13.sp, color = Verdict.MALICIOUS.color,
                    fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ReasonsCard(result: VerifiedResult) {
    if (result.report.reasons.isEmpty()) return
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Pourquoi ce verdict ?", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            result.report.reasons.forEach {
                Text("•  $it", fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun Actions(
    verdict: Verdict,
    result: VerifiedResult,
    onOpenSandbox: (String) -> Unit,
    onReport: () -> Unit,
) {
    val url = result.report.finalTarget ?: result.report.displayedValue
    when (verdict.opening) {
        OpeningPolicy.ALLOWED_WITH_LIGHT_WARNING -> {
            Button(onClick = { onOpenSandbox(url) }, modifier = Modifier.fillMaxWidth()) {
                Text("Ouvrir dans le bac à sable")
            }
            Spacer(Modifier.height(8.dp))
            Text("Vous pouvez aussi l'ouvrir dans votre navigateur (avertissement léger).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        OpeningPolicy.SANDBOX_ONLY -> {
            Text("Ouverture directe bloquée. Forçage possible UNIQUEMENT dans le " +
                "navigateur isolé intégré.", fontSize = 13.sp, color = Verdict.DANGEROUS.color)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onOpenSandbox(url) }, modifier = Modifier.fillMaxWidth()) {
                Text("Forcer dans le navigateur isolé")
            }
        }
        OpeningPolicy.BLOCKED -> {
            Text("Aucune ouverture possible : ce lien est confirmé malveillant.",
                fontSize = 13.sp, color = Verdict.MALICIOUS.color, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onReport) {
                Text("Signaler ce QR code à la communauté (optionnel)")
            }
        }
    }
}

@Composable
private fun SignatureFooter(result: VerifiedResult) {
    val text = if (result.signatureVerified)
        "🔒 Verdict authentifié (signature vérifiée)"
    else
        "⚠️ Signature du verdict NON vérifiée"
    Text(text, fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
}
