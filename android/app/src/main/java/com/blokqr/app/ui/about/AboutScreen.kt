package com.blokqr.app.ui.about
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.blokqr.app.BuildConfig
import com.blokqr.app.Config
import com.blokqr.app.R
import com.blokqr.app.ui.feedback.FeedbackSection
import com.blokqr.app.ui.theme.Blue
import com.blokqr.app.ui.theme.IconBadge
import com.blokqr.app.ui.theme.ScreenContainer
import com.blokqr.app.ui.theme.SectionCard
import com.blokqr.app.ui.theme.Spacing
import com.blokqr.app.ui.util.openUrlExternally
import java.util.Locale
/** Langues pour lesquelles un guide existe (fichiers <code>.pdf</code> hébergés). */
private val GUIDE_LANGS = setOf("fr", "en", "es", "pt", "it", "de", "ar", "tr", "hi", "zh", "ja", "ru")
@Composable
fun AboutScreen(
    onClose: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onShowPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    // Détails de sécurité repliés par défaut : l'écran reste court pour le
    // grand public, les curieux peuvent déplier.
    var securityExpanded by remember { mutableStateOf(false) }
    ScreenContainer(horizontalAlignment = Alignment.CenterHorizontally) {
        // En-tête avec retour.
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
                stringResource(R.string.about_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        IconBadge(Icons.Rounded.QrCodeScanner, Blue)
        Spacer(Modifier.height(Spacing.md))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
        // ACCES RAPIDE AU GUIDE : place en TETE (juste apres l'en-tete) pour un
        // acces immediat sans scroll. C'est l'action principale attendue quand on
        // ouvre cet ecran depuis le bouton d'aide de l'ecran de scan. Le guide
        // reste aussi accessible plus bas ? NON : il est retire de la carte
        // "Plus" pour eviter tout doublon (source unique).
        SectionCard(
            title = stringResource(R.string.about_user_guide),
            icon = Icons.AutoMirrored.Rounded.MenuBook,
        ) {
            Text(
                stringResource(R.string.about_guide_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            ActionRow(
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                label = stringResource(R.string.about_user_guide_open),
                onClick = { openUserGuide(context) },
            )
        }
        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = stringResource(R.string.about_section_description),
            icon = Icons.Rounded.Info,
        ) {
            Text(
                stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = stringResource(R.string.about_section_features),
            icon = Icons.Rounded.AutoAwesome,
        ) {
            val features = listOf(
                stringResource(R.string.about_feature_airgap),
                stringResource(R.string.about_feature_generator),
                stringResource(R.string.about_feature_provenance),
                stringResource(R.string.about_feature_tiers),
                stringResource(R.string.about_feature_signed),
                stringResource(R.string.about_feature_offline),
                stringResource(R.string.about_feature_sandbox),
                stringResource(R.string.about_feature_ai),
                stringResource(R.string.about_feature_privacy),
            )
            features.forEachIndexed { i, f ->
                if (i > 0) Spacer(Modifier.height(Spacing.sm))
                Bullet(f)
            }
        }
        Spacer(Modifier.height(Spacing.md))
        // Liste de menaces locale (Paquet 2) : détection hors-ligne signée.
        SectionCard(
            title = stringResource(R.string.about_blocklist_title),
            icon = Icons.Rounded.GppGood,
        ) {
            Text(
                stringResource(R.string.about_blocklist_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        // Sécurité et confiance : promesse en langage simple, détails REPLIÉS
        // par défaut. Volontairement SANS jargon ni détail d'implémentation :
        // le détail technique vit dans la documentation de référence (diffusée
        // sous NDA), pas dans l'application.
        SectionCard(
            title = stringResource(R.string.about_security_title),
            icon = Icons.Rounded.VerifiedUser,
        ) {
            Text(
                stringResource(R.string.about_security_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.xs))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { securityExpanded = !securityExpanded }
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        if (securityExpanded) R.string.about_security_less
                        else R.string.about_security_more
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (securityExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (securityExpanded) {
                val points = listOf(
                    stringResource(R.string.about_security_point1),
                    stringResource(R.string.about_security_point2),
                    stringResource(R.string.about_security_point3),
                    stringResource(R.string.about_security_point4),
                    stringResource(R.string.about_security_point5),
                )
                points.forEachIndexed { i, p ->
                    if (i > 0) Spacer(Modifier.height(Spacing.sm))
                    Bullet(p)
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        // Carte « Plus » : confidentialité (in-app + en ligne) + revoir l'intro.
        // NB : le guide d'utilisation a ete remonte en tete d'ecran (acces rapide)
        // et n'apparait donc plus ici.
        SectionCard(
            title = stringResource(R.string.about_section_more),
            icon = Icons.Rounded.Info,
        ) {
            // Ouvre la politique INTÉGRÉE (consultable hors-ligne).
            ActionRow(
                icon = Icons.Rounded.PrivacyTip,
                label = stringResource(R.string.about_privacy_policy),
                onClick = onShowPrivacy,
            )
            Spacer(Modifier.height(Spacing.xs))
            // Ouvre la politique EN LIGNE dans le navigateur.
            ActionRow(
                icon = Icons.Rounded.Public,
                label = stringResource(R.string.about_privacy_policy_online),
                onClick = { context.openUrlExternally(Config.PRIVACY_POLICY_URL) },
            )
            Spacer(Modifier.height(Spacing.xs))
            ActionRow(
                icon = Icons.Rounded.RestartAlt,
                label = stringResource(R.string.about_replay_onboarding),
                onClick = onReplayOnboarding,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        // Avis & contact (In-App Review public + email privé).
        FeedbackSection()
        Spacer(Modifier.height(Spacing.xl))
        // Version affichée dynamiquement depuis build.gradle.kts (versionName),
        // pour qu'elle reste toujours synchronisée sans éditer les chaînes.
        Text(
            "BlokQR v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
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
            icon, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
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
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}/**
 * Ouvre le guide d'utilisation (PDF héberge) dans la langue ACTIVE de l'app.
 * Locale.getDefault() reflète le choix utilisateur ou la langue système (via
 * LocaleHelper). Repli sur l'anglais si la langue n'a pas encore de guide.
 * Factorise l'ouverture, appelee depuis la carte Guide en tete d'ecran.
 */
private fun openUserGuide(context: android.content.Context) {
    val code = Locale.getDefault().language.lowercase()
    val lang = if (code in GUIDE_LANGS) code else "en"
    context.openUrlExternally("${Config.USER_GUIDE_BASE_URL}/$lang.pdf")
}
