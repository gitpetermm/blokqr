package com.blokqr.app.ui.paywall
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blokqr.app.R
import com.blokqr.app.billing.BlokQrBilling
import com.blokqr.app.ui.theme.Blue
import com.blokqr.app.ui.theme.IconBadge
import com.blokqr.app.ui.theme.PrimaryButton
import com.blokqr.app.ui.theme.ScreenContainer
import com.blokqr.app.ui.theme.SecondaryButton
import com.blokqr.app.ui.theme.SectionCard
import com.blokqr.app.ui.theme.Spacing
@Composable
fun PaywallScreen(onClose: () -> Unit, vm: PaywallViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val isPro by vm.isPro.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(state) {
        if (state is PaywallViewModel.UiState.Success) onClose()
    }
    ScreenContainer(horizontalAlignment = Alignment.CenterHorizontally) {
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
                stringResource(R.string.paywall_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        IconBadge(Icons.Rounded.Verified, Blue)
        Spacer(Modifier.height(Spacing.md))
        Text(
            stringResource(R.string.paywall_headline),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            stringResource(R.string.paywall_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.lg))
        SectionCard(
            title = stringResource(R.string.paywall_benefits_title),
            icon = Icons.Rounded.Bolt, accent = Blue,
        ) {
            Benefit(stringResource(R.string.paywall_benefit_unlimited))
            Benefit(stringResource(R.string.paywall_benefit_deep))
            Benefit(stringResource(R.string.paywall_benefit_screenshot))
            Benefit(stringResource(R.string.paywall_benefit_signals))
            Benefit(stringResource(R.string.paywall_benefit_priority))
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            stringResource(R.string.paywall_unlimited_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            stringResource(R.string.paywall_same_engine),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Transparence : le générateur et la reconnaissance produit sont gratuits ;
        // Pro ne concerne que l'analyse de sécurité approfondie/illimitée.
        Spacer(Modifier.height(Spacing.sm))
        Text(
            stringResource(R.string.paywall_free_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.lg))
        when (val s = state) {
            PaywallViewModel.UiState.Loading,
            PaywallViewModel.UiState.Purchasing ->
                CircularProgressIndicator(color = Blue)
            is PaywallViewModel.UiState.Ready -> {
                if (isPro) {
                    Text(
                        stringResource(R.string.paywall_pro_active),
                        style = MaterialTheme.typography.titleMedium,
                        color = Blue,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    SecondaryButton(
                        text = stringResource(R.string.paywall_manage_subscription),
                        onClick = { openManageSubscription(context) },
                        accent = Blue,
                    )
                } else {
                    val activity = context as? Activity
                    PrimaryButton(
                        text = ctaLabel(
                            normalRes = R.string.paywall_cta_annual,
                            trialRes = R.string.paywall_cta_annual_trial,
                            price = s.annualPrice,
                            trialDays = s.annualTrialDays,
                        ),
                        onClick = { activity?.let(vm::purchaseAnnual) },
                        enabled = activity != null,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    SecondaryButton(
                        text = ctaLabel(
                            normalRes = R.string.paywall_cta_monthly,
                            trialRes = R.string.paywall_cta_monthly_trial,
                            price = s.monthlyPrice,
                            trialDays = s.monthlyTrialDays,
                        ),
                        onClick = { activity?.let(vm::purchaseMonthly) },
                        accent = Blue,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        stringResource(R.string.paywall_restore_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        stringResource(R.string.paywall_manage_subscription),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Blue,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(role = Role.Button) { openManageSubscription(context) }
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    )
                }
            }
            is PaywallViewModel.UiState.Error -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.md))
                SecondaryButton(
                    text = stringResource(R.string.action_back),
                    onClick = onClose, accent = Blue,
                )
            }
            PaywallViewModel.UiState.Success -> Unit
        }
    }
}
@Composable
private fun Benefit(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.Verified, null, tint = Blue,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
@Composable
private fun ctaLabel(normalRes: Int, trialRes: Int, price: String?, trialDays: Int?): String =
    if (trialDays != null && price != null) {
        stringResource(trialRes, trialDays, price)
    } else {
        stringResource(normalRes, price ?: "—")
    }
private fun openManageSubscription(context: Context) {
    val url = "https://play.google.com/store/account/subscriptions" +
        "?sku=${BlokQrBilling.PRODUCT_ID}&package=${context.packageName}"
    val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .setPackage("com.android.vending")
    val ok = runCatching { context.startActivity(playIntent) }.isSuccess
    if (!ok) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
