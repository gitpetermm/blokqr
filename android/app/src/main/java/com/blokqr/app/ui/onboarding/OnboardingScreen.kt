package com.blokqr.app.ui.onboarding
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dangerous
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blokqr.app.R
import com.blokqr.app.ui.legal.PrivacyPolicyScreen
import com.blokqr.app.ui.theme.DangerAmber
import com.blokqr.app.ui.theme.Dimens
import com.blokqr.app.ui.theme.IconBadge
import com.blokqr.app.ui.theme.InfoChip
import com.blokqr.app.ui.theme.MaliciousRed
import com.blokqr.app.ui.theme.PrimaryButton
import com.blokqr.app.ui.theme.SafeGreen
import com.blokqr.app.ui.theme.Spacing
import kotlinx.coroutines.launch
/**
 * Onboarding affiché au premier lancement.
 *
 * 8 pages (une idée par page), swipe + bouton, points de progression et un
 * « Passer » toujours visible (qui saute à la page finale). La 4e page est une
 * DÉMONSTRATION animée de la détonation (un lien court se déplie et révèle une
 * destination dangereuse + un verdict). La 6e page met en avant la protection
 * HORS-LIGNE. La dernière présente Pro + l'essai 14 jours et porte le bouton
 * « Commencer », qui demande la permission caméra puis entre dans l'app
 * (onFinish). Le lien de confidentialité ouvre l'écran intégré (aucun appel
 * navigateur).
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var showPolicy by remember { mutableStateOf(false) }
    if (showPolicy) {
        PrivacyPolicyScreen(onClose = { showPolicy = false })
        return
    }
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = listOf(
        OnbPage(Icons.Rounded.QrCodeScanner, cs.primary, R.string.onb1_title, R.string.onb1_body),
        OnbPage(Icons.Rounded.PhonelinkLock, cs.primary, R.string.onb2_title, R.string.onb2_body),
        OnbPage(Icons.AutoMirrored.Rounded.AltRoute, cs.primary, R.string.onb3_title, R.string.onb3_body),
        // Page DÉMO : rendu spécial (kind = DEMO), les res title/body servent de
        // légende sous l'animation.
        OnbPage(Icons.Rounded.Visibility, cs.primary, R.string.onb_demo_title, R.string.onb_demo_body, kind = OnbKind.DEMO),
        OnbPage(Icons.Rounded.Verified, SafeGreen, R.string.onb4_title, R.string.onb4_body),
        OnbPage(Icons.Rounded.WifiOff, cs.primary, R.string.onb5_title, R.string.onb5_body),
        OnbPage(Icons.Rounded.QrCode2, cs.primary, R.string.onb_create_title, R.string.onb_create_body),
        OnbPage(Icons.Rounded.WorkspacePremium, cs.primary, R.string.onb6_title, R.string.onb6_body, kind = OnbKind.PRO),
    )
    val lastIndex = pages.lastIndex
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val isLast = pagerState.currentPage == lastIndex
    val camLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> onFinish() }
    fun start() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onFinish() else camLauncher.launch(Manifest.permission.CAMERA)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.background, cs.surface, cs.background)))
            .systemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = Spacing.md),
            ) {
                if (!isLast) {
                    TextButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(lastIndex) } },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Text(stringResource(R.string.onb_skip), color = cs.onSurfaceVariant)
                    }
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                // La page DÉMO ne s'anime que lorsqu'elle est réellement à l'écran.
                OnboardingPageContent(pages[page], active = page == pagerState.currentPage)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.md),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pages.size) { i ->
                    val selected = i == pagerState.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (selected) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(if (selected) cs.primary else cs.outline),
                    )
                }
            }
            Column(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = Dimens.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPadding)
                    .padding(bottom = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (isLast) {
                    PrimaryButton(
                        text = stringResource(R.string.onb_get_started),
                        onClick = { start() },
                        icon = Icons.Rounded.PhotoCamera,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        stringResource(R.string.onb_privacy_full),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { showPolicy = true }
                            .padding(vertical = 6.dp),
                    )
                } else {
                    PrimaryButton(
                        text = stringResource(R.string.onb_next),
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
                    )
                }
            }
        }
    }
}
private enum class OnbKind { STANDARD, DEMO, PRO }
private data class OnbPage(
    val icon: ImageVector,
    val color: Color,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    val kind: OnbKind = OnbKind.STANDARD,
)
@Composable
private fun OnboardingPageContent(page: OnbPage, active: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenPadding)
                .padding(vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (page.kind == OnbKind.DEMO) {
                DetonationDemo(active = active)
                Spacer(Modifier.height(Spacing.xl))
            } else {
                IconBadge(page.icon, page.color, size = 104.dp)
                Spacer(Modifier.height(Spacing.xl))
            }
            Text(
                stringResource(page.title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                stringResource(page.body),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (page.kind == OnbKind.PRO) {
                Spacer(Modifier.height(Spacing.lg))
                InfoChip(
                    text = stringResource(R.string.onb6_trial),
                    color = DangerAmber,
                    icon = Icons.Rounded.Bolt,
                )
                Spacer(Modifier.height(Spacing.lg))
                ProBullet(stringResource(R.string.paywall_benefit_unlimited))
                ProBullet(stringResource(R.string.paywall_benefit_deep))
                ProBullet(stringResource(R.string.paywall_benefit_screenshot))
                ProBullet(stringResource(R.string.paywall_benefit_signals))
                ProBullet(stringResource(R.string.paywall_benefit_priority))
                Spacer(Modifier.height(Spacing.md))
                Text(
                    stringResource(R.string.onb6_unlimited_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
/**
 * Démonstration animée de la détonation, purement illustrative (aucun réseau) :
 *  1) un lien court s'affiche,
 *  2) il se « déplie » en révélant la vraie destination (dangereuse),
 *  3) un verdict « Danger » apparaît.
 * La séquence se (re)joue à chaque fois que la page devient active.
 */
@Composable
private fun DetonationDemo(active: Boolean) {
    val cs = MaterialTheme.colorScheme
    // Étapes révélées progressivement quand la page est active.
    var step by remember { mutableStateOf(0) }
    LaunchedEffect(active) {
        if (active) {
            step = 0
            kotlinx.coroutines.delay(350); step = 1   // lien court
            kotlinx.coroutines.delay(650); step = 2   // dépliage destination
            kotlinx.coroutines.delay(750); step = 3   // verdict
        } else {
            step = 0
        }
    }
    // Trait de progression du « scan » (0 -> 1) tant qu'on n'a pas le verdict.
    val progress by animateFloatAsState(
        targetValue = if (step >= 2) 1f else if (step >= 1) 0.5f else 0f,
        animationSpec = tween(durationMillis = 600, easing = LinearEasing),
        label = "demo_progress",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.4f))
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Lien court scanné.
        DemoUrlChip(
            label = "bit.ly/win-A7x",
            color = cs.onSurfaceVariant,
            leading = Icons.Rounded.QrCode2,
        )
        // Barre de « détonation » en cours.
        Spacer(Modifier.height(Spacing.md))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(cs.outline.copy(alpha = 0.4f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(if (step >= 3) MaliciousRed else cs.primary),
            )
        }
        // Destination réelle révélée (dépliage).
        AnimatedVisibility(
            visible = step >= 2,
            enter = fadeIn() + expandVertically(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(Spacing.md))
                Icon(
                    Icons.AutoMirrored.Rounded.AltRoute, null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(Spacing.sm))
                DemoUrlChip(
                    label = "paypa1-secure.tk/login",
                    color = MaliciousRed,
                    leading = Icons.Rounded.Dangerous,
                )
            }
        }
        // Verdict final.
        AnimatedVisibility(
            visible = step >= 3,
            enter = fadeIn() + scaleIn(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(Spacing.md))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaliciousRed.copy(alpha = 0.16f))
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Dangerous, null,
                        tint = MaliciousRed,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        stringResource(R.string.result_verdict_malicious),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaliciousRed,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
@Composable
private fun DemoUrlChip(label: String, color: Color, leading: ImageVector) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(leading, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
@Composable
private fun ProBullet(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.Check, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}