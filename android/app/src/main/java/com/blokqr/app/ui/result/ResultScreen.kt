@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.blokqr.app.ui.result
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoCall
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
import com.blokqr.app.ai.PhishingClassifier
import com.blokqr.app.model.LocalReason
import com.blokqr.app.model.OpeningPolicy
import com.blokqr.app.model.Verdict
import com.blokqr.app.model.VerifiedResult
import com.blokqr.app.security.QuotaManager
import com.blokqr.app.ui.quota.LocalModeBanner
import com.blokqr.app.ui.quota.QuotaBanner
import com.blokqr.app.ui.tools.SmartActionParser
import com.blokqr.app.ui.tools.SmartActions
import com.blokqr.app.ui.util.openUrlExternally
import com.blokqr.app.ui.theme.*
/** Hauteur de la zone d'aperçu (capture serveur). Partagée entre le décodage
 *  sous-échantillonné et l'affichage pour rester cohérents. */
private val PREVIEW_HEIGHT = 220.dp
/**
 * Décode un PNG/JPEG en le SOUS-ÉCHANTILLONNANT à la taille d'affichage.
 * Deux passes : (1) lecture des seules dimensions (inJustDecodeBounds), (2)
 * calcul d'inSampleSize (puissance de 2) pour que la hauteur décodée reste
 * >= targetHeightPx, puis décodage réel. Évite de charger en mémoire une image
 * bien plus grande que la vue (recommandation Play / Android Vitals).
 */
private fun decodeSampled(bytes: ByteArray, targetHeightPx: Int): android.graphics.Bitmap? {
    // Toujours passer un BitmapFactory.Options (meme au repli) : l'analyseur
    // statique de Play signale tout decodeByteArray sans Options.
    if (targetHeightPx <= 0) {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options())
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val srcH = bounds.outHeight
    if (srcH <= 0) return null
    var sample = 1
    while (srcH / (sample * 2) >= targetHeightPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
/**
 * Écran de résultat — découpage Free/Pro :
 *
 *   Free : verdict signé + URL finale + nombre de redirections + 1 raison
 *          principale (teaser « + N autres motifs »). Action sécurisée
 *          (sandbox / bloqué). TrustBadge. Bouton « Analyse approfondie »
 *          qui ouvre le paywall.
 *
 *   Pro  : tout ce qui précède + liste complète des raisons, chips de tous
 *          les signaux, chaîne de redirection détaillée, drapeaux contextuels,
 *          screenshot, détection IA d'usurpation, login impersonation.
 *
 * Aucune dégradation de la promesse de sécurité côté Free : la décision
 * (Safe/Caution/Dangerous/Malicious) reste signée et auditée par le client ;
 * c'est uniquement la RICHESSE d'analyse qui est paywall.
 *
 * Mode LOCAL (localMode=true) : le résultat provient de l'analyse lexicale
 * hors-ligne. Un bandeau d'avertissement explicite est affiché — adapté à la
 * raison (hors-ligne, initialisation, serveur, quota) via `localReason` —
 * rappelant que ce verdict n'est PAS vérifié par le serveur (garde-fou).
 *
 * UX : un retour haptique calibré sur la sévérité du verdict est émis à
 * l'affichage, et une ligne « Copier / Partager / Image » permet d'agir sur la
 * destination (et de partager une carte de verdict) sans jamais l'ouvrir.
 */
@Composable
fun ResultScreen(
    result: VerifiedResult,
    aiAssessment: PhishingClassifier.Assessment?,
    onOpenSandbox: (String) -> Unit,
    onRescan: () -> Unit,
    deepening: Boolean = false,
    isPro: Boolean = false,
    onUpgrade: () -> Unit = {},
    localMode: Boolean = false,
    localReason: LocalReason? = null,
    deepPreviewOffered: Boolean = false,
) {
    val verdict = if (!result.isNavigable) Verdict.NEUTRAL else result.verdict
    // Vue riche : soit l'utilisateur est Pro, soit il bénéficie de l'aperçu
    // approfondi offert du jour. Le verdict signé reste identique dans les deux
    // cas : seule la RICHESSE d'affichage (capture, signaux, IA) change.
    val richView = isPro || deepPreviewOffered
    val context = LocalContext.current
    val shareValue = result.report.finalTarget ?: result.report.displayedValue
    // Retour haptique discret à l'apparition du verdict (calibré par sévérité).
    LaunchedEffect(shareValue, verdict) {
        context.vibrateForVerdict(verdict)
    }
    ScreenContainer(horizontalAlignment = Alignment.CenterHorizontally) {
        // Barre du haut : flèche de retour vers le scanner (sortie toujours
        // possible sans devoir scroller jusqu'au bouton « Scanner un autre code »).
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRescan) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        // Bandeau « analyse locale — non vérifiée » (garde-fou mode dégradé).
        if (localMode) {
            Spacer(Modifier.height(Spacing.sm))
            LocalModeBanner(reason = localReason)
        }
        // Bandeau « il vous reste N analyses » (near limit, hors mode local/Pro).
        if (!localMode && !isPro) {
            QuotaManager.last?.let { snap ->
                if (snap.isNearLimit) {
                    Spacer(Modifier.height(Spacing.sm))
                    QuotaBanner(snapshot = snap, onUpgrade = onUpgrade)
                }
            }
        }
        // Bandeau « aperçu approfondi offert » : cadeau du jour, ton positif.
        if (deepPreviewOffered) {
            Spacer(Modifier.height(Spacing.sm))
            DeepPreviewBanner()
        }
        Spacer(Modifier.height(Spacing.md))
        VerdictHeader(verdict, result.score)
        // Etiquette de CATEGORIE lisible (ex. « Hameçonnage bancaire »), deduite
        // localement des signaux, pour un verdict a risque et un contenu navigable.
        val atRisk = verdict == Verdict.MALICIOUS || verdict == Verdict.DANGEROUS ||
                verdict == Verdict.UNKNOWN
        if (result.isNavigable && atRisk) {
            ThreatCategory.of(result)?.let { cat ->
                Spacer(Modifier.height(Spacing.sm))
                ThreatCategoryLabel(cat, verdict)
            }
        }
        Spacer(Modifier.height(Spacing.lg))
        if (deepening) {
            DeepeningRow()
            Spacer(Modifier.height(Spacing.md))
        }
        if (!result.isNavigable) {
            DecodedCard(result)
            // Contenu non-web actionnable (geo, tel, sms, e-mail, contact,
            // évènement) -> bouton de transfert direct vers l'app dédiée. Ces
            // intents ouvrent un éditeur/composeur que l'utilisateur valide :
            // aucun risque de phishing (schémas non web uniquement).
            val smart = remember(result.report.displayedValue) {
                SmartActions.forContent(result.report.displayedValue)
            }
            if (smart != null) {
                Spacer(Modifier.height(Spacing.md))
                PrimaryButton(
                    text = stringResource(smart.labelRes),
                    onClick = { smart.launch(context) },
                    icon = smart.icon,
                )
            }
        } else {
            // Destination : URL + nb de redirections (texte) toujours en Free.
            // Drapeaux + chaîne détaillée : Pro uniquement.
            DestinationCard(result, isPro = richView, onUpgrade = onUpgrade)
            // Aperçu (screenshot + IA + login impersonation) : Pro uniquement,
            // teaser pour les Free (rendu Chromium côté serveur = coût réel).
            Spacer(Modifier.height(Spacing.md))
            if (richView) {
                PreviewCard(result, aiAssessment, verdict)
            } else {
                LockedPreviewCard(onUpgrade)
            }
            // Raisons : top 1 en Free (+ teaser nombre restant), liste complète
            // + chips en Pro.
            val hasReasons = result.report.reasons.isNotEmpty() ||
                    result.report.signals.isNotEmpty()
            if (hasReasons) {
                Spacer(Modifier.height(Spacing.md))
                ReasonsCard(result, isPro = richView, onUpgrade = onUpgrade)
            }
            Spacer(Modifier.height(Spacing.lg))
            ActionsBlock(verdict, result, onOpenSandbox)
            // CTA paywall persistant pour Free (analyse approfondie). Message
            // CONTEXTUEL : si l'aperçu est offert aujourd'hui, on souligne que
            // c'est le moment de garder cette vue en permanence avec Pro.
            if (!isPro && !deepening) {
                Spacer(Modifier.height(Spacing.md))
                if (deepPreviewOffered) DeepPreviewUpsell(onUpgrade) else ProUpsell(onUpgrade)
            }
        }
        // Copier l'URL / Partager le verdict / Partager l'image (sans jamais
        // ouvrir le lien).
        Spacer(Modifier.height(Spacing.lg))
        ShareCopyRow(shareValue, verdict, result)
        Spacer(Modifier.height(Spacing.xl))
        TrustBadge(
            verified = result.signatureVerified,
            verifiedLabel = stringResource(R.string.result_trust_verified),
            unverifiedLabel = stringResource(R.string.result_trust_unverified),
        )
        Spacer(Modifier.height(Spacing.md))
        SecondaryButton(
            text = stringResource(R.string.result_action_rescan),
            onClick = onRescan,
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}
/* ----------------------------- Categorie de menace ----------------------- */
/** Etiquette lisible de la categorie de menace, sous le verdict. */
@Composable
private fun ThreatCategoryLabel(category: ThreatCategory, verdict: Verdict) {
    val color = when (verdict) {
        Verdict.MALICIOUS -> MaliciousRed
        Verdict.DANGEROUS -> DangerAmber
        else -> NeutralGrey
    }
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Category, null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            stringResource(category.labelRes),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
/* ----------------------------- En-tête ----------------------------------- */
@Composable
private fun VerdictHeader(verdict: Verdict, score: Int) {
    val accent = verdict.color
    // Animation de revelation : le badge « apparait » en ressort (scale + fondu)
    // a l'affichage du verdict, synchronise avec le retour haptique emis dans
    // ResultScreen. Rejouee a chaque nouveau verdict (cle sur `verdict`).
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(verdict) {
        reveal.snapTo(0f)
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.52f,               // leger rebond -> effet « pop »
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }
    val p = reveal.value
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(accent.copy(alpha = 0.12f))
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Badge : scale 0.6 -> 1.0 (avec depassement du ressort) + fondu.
        Box(
            Modifier.graphicsLayer {
                val s = 0.6f + 0.4f * p
                scaleX = s
                scaleY = s
                alpha = p.coerceIn(0f, 1f)
            },
        ) {
            IconBadge(verdict.icon(), accent)
        }
        Spacer(Modifier.height(Spacing.md))
        // Textes + jauge : fondu doux, dans la foulee du badge.
        Column(
            Modifier.graphicsLayer { alpha = p.coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(verdictLabelRes(verdict)),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                stringResource(verdictHeadlineRes(verdict)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.lg))
            RiskMeter(score, accent, stringResource(R.string.result_risk_label))
        }
    }
}
/* ----------------------------- Contenu neutre ---------------------------- */
@Composable
private fun DecodedCard(result: VerifiedResult) {
    SectionCard(
        title = stringResource(R.string.result_section_decoded),
        icon = Icons.Rounded.Public,
    ) {
        Text(
            result.report.displayedValue,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
/* ----------------------------- Destination ------------------------------- */
@Composable
private fun DestinationCard(
    result: VerifiedResult,
    isPro: Boolean,
    onUpgrade: () -> Unit,
) {
    val r = result.report
    SectionCard(
        title = stringResource(R.string.result_section_destination),
        icon = Icons.Rounded.Public,
    ) {
        // URL finale : essentiel à la décision, toujours visible.
        LabeledValue(
            label = stringResource(R.string.result_final_target),
            value = r.finalTarget ?: r.displayedValue,
        )
        // Nombre de redirections (information de base) : toujours en Free.
        val hops = r.redirectChain.size
        if (hops > 1) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.result_redirects, hops),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Drapeaux contextuels (cloaking, gating, QRLJacking, consensus...) :
        // signaux d'analyste, Pro uniquement.
        if (isPro) {
            val flags = buildFlags(result)
            if (flags.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.md))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    flags.forEach { (label, color) -> InfoChip(label, color) }
                }
            }
        } else {
            // Teaser Pro : indique qu'une chaîne détaillée et des drapeaux
            // existent, sans les exposer.
            if (hops > 1 || buildFlagsCount(result) > 0) {
                Spacer(Modifier.height(Spacing.sm))
                LockedTeaser(
                    text = stringResource(R.string.result_redirect_chain_pro),
                    onClick = onUpgrade,
                )
            }
        }
    }
}
/* ----------------------------- Aperçu (Pro) ------------------------------ */
@Composable
private fun PreviewCard(
    result: VerifiedResult,
    ai: PhishingClassifier.Assessment?,
    verdict: Verdict,
) {
    SectionCard(
        title = stringResource(R.string.result_section_preview),
        icon = Icons.Rounded.Photo,
    ) {
        val b64 = result.report.screenshotB64
        // Décodage SOUS-ÉCHANTILLONNÉ : la capture serveur peut être plus grande
        // que la zone d'affichage (220 dp de haut). On décode donc à la taille
        // utile via inSampleSize, ce qui réduit fortement la mémoire (recommandé
        // par Play / Android Vitals) sans changement visuel.
        val targetPx = with(LocalDensity.current) { PREVIEW_HEIGHT.roundToPx() }
        val bmp = remember(b64, targetPx) {
            if (b64.isNullOrEmpty()) null
            else runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                ?.let { decodeSampled(it, targetPx) }
        }
        if (bmp != null) {
            // Pour un verdict dangereux/malveillant, la capture n'est PAS rendue
            // par défaut : un voile d'avertissement + « Afficher quand même »
            // évite de présenter une page frauduleuse de façon crédible et
            // protège d'un contenu choquant. (Voile opaque plutôt que
            // Modifier.blur, indisponible avant Android 12 / minSdk 29 — la
            // capture n'est jamais dessinée tant qu'elle n'est pas révélée.)
            val sensitive = verdict == Verdict.MALICIOUS || verdict == Verdict.DANGEROUS
            var revealed by remember(b64) { mutableStateOf(!sensitive) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PREVIEW_HEIGHT)
                    .clip(MaterialTheme.shapes.small),
            ) {
                if (revealed) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.result_section_preview),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaliciousRed.copy(alpha = 0.12f))
                            .clickable { revealed = true }
                            .padding(Spacing.lg),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.VisibilityOff, null,
                            tint = MaliciousRed,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            stringResource(R.string.result_preview_sensitive),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            stringResource(R.string.result_preview_reveal),
                            style = MaterialTheme.typography.labelLarge,
                            color = Blue,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        } else {
            Text(
                stringResource(R.string.result_preview_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ai != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(
                    R.string.result_ai_assessment,
                    ai.label,
                    (ai.impersonationProbability * 100).toInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        result.report.loginImpersonation?.let {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                stringResource(R.string.result_impersonation, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaliciousRed,
            )
        }
    }
}
/** Carte « Aperçu verrouillé » côté Free : teaser de la feature Pro. */
@Composable
private fun LockedPreviewCard(onUpgrade: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.result_advanced_locked_title),
        icon = Icons.Rounded.Lock,
    ) {
        Text(
            stringResource(R.string.result_advanced_locked_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        SecondaryButton(
            text = stringResource(R.string.result_action_deep_pro),
            onClick = onUpgrade,
            icon = Icons.Rounded.Bolt,
            accent = Blue,
        )
    }
}
/* ----------------------------- Raisons / signaux ------------------------- */
@Composable
private fun ReasonsCard(
    result: VerifiedResult,
    isPro: Boolean,
    onUpgrade: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.result_section_reasons),
        icon = Icons.Rounded.Lightbulb,
    ) {
        // Le service n'envoie qu'un CODE stable ; le libellé affiché provient des
        // ressources de l'app (et suit donc la langue choisie). Repli sur le titre
        // serveur si un code n'est pas (encore) connu de l'application.
        val ranked = result.report.signals.sortedByDescending { it.weight }
        if (ranked.isEmpty()) {
            ReasonBullet(stringResource(R.string.reasons_none))
            return@SectionCard
        }
        // Free : on n'affiche QUE la 1re raison (la plus sévère).
        // Pro  : on affiche les 5 principales (comme avant), puis tous les chips.
        val visible = if (isPro) ranked.take(5) else ranked.take(1)
        visible.forEachIndexed { i, s ->
            if (i > 0) Spacer(Modifier.height(Spacing.sm))
            ReasonBullet(signalTitle(s.code, s.title))
        }
        if (!isPro) {
            // Teaser du nombre de motifs cachés (s'il y en a au moins 1 de plus).
            val remaining = ranked.size - 1
            if (remaining > 0) {
                Spacer(Modifier.height(Spacing.md))
                LockedTeaser(
                    text = stringResource(R.string.result_more_reasons_pro, remaining),
                    onClick = onUpgrade,
                )
            }
        } else {
            // Pro : chips de tous les signaux pour la vue exhaustive.
            val signals = result.report.signals
            if (signals.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.md))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    signals.forEach { s ->
                        InfoChip(signalTitle(s.code, s.title), severityColor(s.severity))
                    }
                }
            }
        }
    }
}
/** Une puce de raison. */
@Composable
private fun ReasonBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "•",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
/** Ligne « verrouillée » cliquable qui ouvre le paywall. */
@Composable
private fun LockedTeaser(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .background(Blue.copy(alpha = 0.08f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Lock, null,
            tint = Blue,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = Blue,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}
/* ----------------------------- Copier / Partager / Image ----------------- */
@Composable
private fun ShareCopyRow(value: String, verdict: Verdict, result: VerifiedResult) {
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.app_name)
    val brand = stringResource(R.string.app_name)
    val verdictLabel = stringResource(verdictLabelRes(verdict))
    val headline = stringResource(verdictHeadlineRes(verdict))
    val prefix = stringResource(R.string.result_share_verdict_prefix)
    val footer = stringResource(R.string.result_share_footer)
    val cardFooter = stringResource(R.string.result_share_card_footer)
    val chooserTitle = stringResource(R.string.result_share_image_chooser)
    val hops = result.report.redirectChain.size
    val redirectsLine = if (hops > 1) stringResource(R.string.result_redirects, hops) else null
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = { context.copyToClipboard(clipLabel, value) }) {
            Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(stringResource(R.string.result_action_copy))
        }
        TextButton(onClick = {
            val summary = value + "\n" + prefix + " " + verdictLabel + "\n" + footer
            context.shareVerdict(summary)
        }) {
            Icon(Icons.Rounded.Share, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(stringResource(R.string.result_action_share))
        }
        TextButton(onClick = {
            val card = VerdictCardRenderer.CardText(
                verdictLabel = verdictLabel,
                headline = headline,
                url = value,
                redirectsLine = redirectsLine,
                footer = cardFooter,
                brand = brand,
            )
            VerdictCardRenderer.render(context, verdict, card)?.let { uri ->
                context.shareVerdictImage(uri, chooserTitle)
            }
        }) {
            Icon(Icons.Rounded.Image, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(stringResource(R.string.result_action_share_image))
        }
    }
}
/**
 * Libellé localisé d'un signal d'après son code stable. Repli sur le titre
 * fourni par le service si le code n'est pas (encore) connu de l'application.
 */
@Composable
private fun signalTitle(code: String, fallback: String): String {
    // Signaux de threat intelligence : code dynamique "ti_<provider>"
    // (ex. "ti_google_web_risk"). Libellé de base LOCALISÉ + provider humanisé,
    // indépendant de la langue du titre rédigé par le serveur.
    if (code.startsWith("ti_")) {
        val provider = prettifySignalCode(code.removePrefix("ti_"))
        return stringResource(R.string.signal_malicious_reputation, provider)
    }
    return when (code) {
    "analysis_timeout" -> stringResource(R.string.signal_analysis_timeout)
    "antibot_gating" -> stringResource(R.string.signal_antibot_gating)
    "brand_combosquatting" -> stringResource(R.string.signal_brand_combosquatting)
    "capability_url" -> stringResource(R.string.signal_capability_url)
    "cloaking_detected" -> stringResource(R.string.signal_cloaking_detected)
    "crypto_payment" -> stringResource(R.string.signal_crypto_payment)
    "deep_link" -> stringResource(R.string.signal_deep_link)
    "destination_changed" -> stringResource(R.string.signal_destination_changed)
    "destination_diverges_consensus" -> stringResource(R.string.signal_destination_diverges_consensus)
    "destination_stable" -> stringResource(R.string.signal_destination_stable)
    "domain_unresolved" -> stringResource(R.string.signal_domain_unresolved)
    "dynamic_error" -> stringResource(R.string.signal_dynamic_error)
    "embedded_credentials" -> stringResource(R.string.signal_embedded_credentials)
    "excessive_subdomains" -> stringResource(R.string.signal_excessive_subdomains)
    "executable_target" -> stringResource(R.string.signal_executable_target)
    "google_web_risk" -> stringResource(R.string.signal_google_web_risk)
    "hidden_wifi" -> stringResource(R.string.signal_hidden_wifi)
    "high_entropy_domain" -> stringResource(R.string.signal_high_entropy_domain)
    "high_risk_tld" -> stringResource(R.string.signal_high_risk_tld)
    "hop_unreachable" -> stringResource(R.string.signal_hop_unreachable)
    "idn_homoglyph" -> stringResource(R.string.signal_idn_homoglyph)
    "ip_literal_host" -> stringResource(R.string.signal_ip_literal_host)
    "js_redirect" -> stringResource(R.string.signal_js_redirect)
    "login_impersonation" -> stringResource(R.string.signal_login_impersonation)
    "long_redirect_chain" -> stringResource(R.string.signal_long_redirect_chain)
    "lookalike_domain" -> stringResource(R.string.signal_lookalike_domain)
    "meta_refresh_redirect" -> stringResource(R.string.signal_meta_refresh_redirect)
    "newly_seen_domain" -> stringResource(R.string.signal_newly_seen_domain)
    "non_ascii_host" -> stringResource(R.string.signal_non_ascii_host)
    "open_redirect_param" -> stringResource(R.string.signal_open_redirect_param)
    "open_wifi" -> stringResource(R.string.signal_open_wifi)
    "password_form" -> stringResource(R.string.signal_password_form)
    "phishing_keywords" -> stringResource(R.string.signal_phishing_keywords)
    "punycode_idn" -> stringResource(R.string.signal_punycode_idn)
    "qr_login_endpoint" -> stringResource(R.string.signal_qr_login_endpoint)
    "redirect_loop" -> stringResource(R.string.signal_redirect_loop)
    "sms_action" -> stringResource(R.string.signal_sms_action)
    "ssrf_blocked_hop" -> stringResource(R.string.signal_ssrf_blocked_hop)
    "ssrf_blocked_render" -> stringResource(R.string.signal_ssrf_blocked_render)
    "tls_downgrade" -> stringResource(R.string.signal_tls_downgrade)
    "too_many_redirects" -> stringResource(R.string.signal_too_many_redirects)
    "unresolved_redirect" -> stringResource(R.string.signal_unresolved_redirect)
    "unusual_port" -> stringResource(R.string.signal_unusual_port)
    "url_shortener" -> stringResource(R.string.signal_url_shortener)
    "young_domain" -> stringResource(R.string.signal_young_domain)
    else -> {
        val label = fallback.ifBlank { code }
        when {
            // Code brut isolé : "google_web_risk" -> "Google Web Risk".
            label.matches(Regex("^[a-z0-9_]+$")) -> prettifySignalCode(label)
            // Titre annoté par le serveur " (source_code)" : on humanise la
            // source entre parenthèses -> "(google_web_risk)" -> "(Google Web Risk)".
            else -> label.replace(Regex("\\(([a-z0-9_]+)\\)")) { m ->
                "(" + prettifySignalCode(m.groupValues[1]) + ")"
            }
        }
    }
    }
}
/** Humanise un code "snake_case" en libellé : google_web_risk -> Google Web Risk. */
private fun prettifySignalCode(raw: String): String =
    raw.split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
/* ----------------------------- Actions ----------------------------------- */
@Composable
private fun ActionsBlock(
    verdict: Verdict,
    result: VerifiedResult,
    onOpenSandbox: (String) -> Unit,
) {
    val context = LocalContext.current
    val url = result.report.finalTarget ?: result.report.displayedValue
    val verdictLabel = stringResource(verdictLabelRes(verdict))
    val signalCodes = result.report.signals.map { it.code }
    when (verdict.opening) {
        OpeningPolicy.ALLOWED_WITH_LIGHT_WARNING -> {
            PrimaryButton(
                text = stringResource(R.string.result_action_open_sandbox),
                onClick = { onOpenSandbox(url) },
            )
            // Lien de réunion jugé SÛR : proposer de rejoindre directement dans
            // l'app dédiée (Zoom/Teams/Meet). Ouverture externe cohérente avec
            // l'avertissement léger ci-dessous, et RÉSERVÉE aux liens autorisés.
            if (SmartActionParser.isMeetingLink(url)) {
                Spacer(Modifier.height(Spacing.sm))
                SecondaryButton(
                    text = stringResource(R.string.smart_join_meeting),
                    onClick = { context.openUrlExternally(url) },
                    icon = Icons.Rounded.VideoCall,
                    accent = Blue,
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.result_open_browser_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OpeningPolicy.SANDBOX_ONLY -> {
            Text(
                stringResource(R.string.result_sandbox_only_note),
                style = MaterialTheme.typography.bodyMedium,
                color = DangerAmber,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            SecondaryButton(
                text = stringResource(R.string.result_action_force_sandbox),
                onClick = { onOpenSandbox(url) },
                accent = DangerAmber,
            )
        }
        OpeningPolicy.BLOCKED -> {
            Text(
                stringResource(R.string.result_blocked_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaliciousRed,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            TextButton(
                onClick = { context.sendReportEmail(url, verdictLabel, signalCodes) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.result_action_report))
            }
        }
    }
}
/* ------------------------- Profondeur (Pro) ------------------------------ */
@Composable
private fun DeepeningRow() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = Blue,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            stringResource(R.string.result_deepening),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
/** Bandeau « aperçu approfondi offert aujourd'hui » (ton positif, cadeau). */
@Composable
private fun DeepPreviewBanner() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(Blue.copy(alpha = 0.12f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Bolt, null,
            tint = Blue,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            stringResource(R.string.result_deep_preview_offered),
            style = MaterialTheme.typography.labelLarge,
            color = Blue,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
/** Upsell CONTEXTUEL au moment de l'aperçu offert : inciter à garder la vue Pro. */
@Composable
private fun DeepPreviewUpsell(onUpgrade: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.result_deep_preview_keep_title),
        icon = Icons.Rounded.Bolt,
    ) {
        Text(
            stringResource(R.string.result_deep_preview_keep_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        PrimaryButton(
            text = stringResource(R.string.result_action_deep_pro),
            onClick = onUpgrade,
        )
    }
}
@Composable
private fun ProUpsell(onUpgrade: () -> Unit) {
    SecondaryButton(
        text = stringResource(R.string.result_action_deep_pro),
        onClick = onUpgrade,
        icon = Icons.Rounded.Bolt,
        accent = Blue,
    )
}
/* ----------------------------- Helpers ----------------------------------- */
@Composable
private fun buildFlags(result: VerifiedResult): List<Pair<String, Color>> {
    val r = result.report
    // Toutes les chaînes résolues inconditionnellement (structure stable).
    val sDest = stringResource(R.string.flag_destination_changed)
    val sCloak = stringResource(R.string.flag_cloaking)
    val sGating = stringResource(R.string.flag_gating)
    val sQrl = stringResource(R.string.flag_qrljacking)
    val sCap = stringResource(R.string.flag_capability)
    val sPriv = stringResource(R.string.flag_privacy_hold)
    val sCons = stringResource(R.string.flag_consensus)
    val out = mutableListOf<Pair<String, Color>>()
    if (r.destinationChanged) out += sDest to DangerAmber
    if (r.cloakingDetected) out += sCloak to MaliciousRed
    if (r.gatingDetected) out += sGating to DangerAmber
    if (r.qrljackingSuspected) out += sQrl to MaliciousRed
    if (r.capabilityUrl) out += sCap to NeutralGrey
    if (r.privacyHold) out += sPriv to NeutralGrey
    if (r.divergesConsensus) out += sCons to DangerAmber
    return out
}
/** Comptage sans @Composable (pour la décision d'afficher le teaser Free). */
private fun buildFlagsCount(result: VerifiedResult): Int {
    val r = result.report
    var n = 0
    if (r.destinationChanged) n++
    if (r.cloakingDetected) n++
    if (r.gatingDetected) n++
    if (r.qrljackingSuspected) n++
    if (r.capabilityUrl) n++
    if (r.privacyHold) n++
    if (r.divergesConsensus) n++
    return n
}
private fun severityColor(severity: String): Color = when (severity.lowercase()) {
    "critical", "high" -> MaliciousRed
    "medium", "low", "warning" -> DangerAmber
    else -> NeutralGrey
}
private fun verdictLabelRes(v: Verdict): Int = when (v) {
    Verdict.SAFE -> R.string.result_verdict_safe
    Verdict.DANGEROUS -> R.string.result_verdict_caution
    Verdict.MALICIOUS -> R.string.result_verdict_malicious
    Verdict.UNKNOWN -> R.string.result_verdict_unknown
    Verdict.NEUTRAL -> R.string.result_verdict_neutral
}
private fun verdictHeadlineRes(v: Verdict): Int = when (v) {
    Verdict.SAFE -> R.string.result_headline_safe
    Verdict.DANGEROUS -> R.string.result_headline_dangerous
    Verdict.MALICIOUS -> R.string.result_headline_malicious
    Verdict.UNKNOWN -> R.string.result_headline_unknown
    Verdict.NEUTRAL -> R.string.result_headline_neutral
}
