package com.blokqr.app.ui
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blokqr.app.ui.about.AboutScreen
import com.blokqr.app.ui.analyze.AnalyzingScreen
import com.blokqr.app.ui.legal.PrivacyPolicyScreen
import com.blokqr.app.ui.paywall.PaywallScreen
import com.blokqr.app.ui.provenance.GtinDecoder
import com.blokqr.app.ui.provenance.GtinInfo
import com.blokqr.app.ui.provenance.ProductProvenanceCard
import com.blokqr.app.ui.quota.QuotaExhaustedScreen
import com.blokqr.app.ui.result.ResultScreen
import com.blokqr.app.ui.sandbox.SandboxActivity
import com.blokqr.app.ui.history.ScanHistoryScreen
import com.blokqr.app.ui.scan.ScannerScreen
import com.blokqr.app.ui.settings.SettingsScreen
import com.blokqr.app.ui.theme.ThemeChoice
import com.blokqr.app.ui.tools.CreateScreen
import com.blokqr.app.ui.url.AnalyzeUrlScreen
import androidx.compose.ui.res.stringResource
import com.blokqr.app.R
import com.blokqr.app.billing.EntitlementUiState
import com.blokqr.app.data.ScanLogEntry
import com.blokqr.app.data.ScanLogStore
import com.blokqr.app.data.SecurityStore
import com.blokqr.app.data.SettingsStore
import com.blokqr.app.model.OpeningPolicy
import com.blokqr.app.ui.security.BiometricGate
import kotlinx.coroutines.launch
/**
 * Aiguillage de l'interface, piloté par l'état du ViewModel.
 *
 * Structure :
 *  - Des OVERLAYS (navigateur isolé, Infos, Paramètres, confidentialité, analyse
 *    d'URL, historique, paywall) s'affichent en PLEIN ÉCRAN via un `return`
 *    anticipé — donc SANS barre d'onglets.
 *  - En l'absence d'overlay, une `Scaffold` + une barre à 2 onglets
 *    (Scanner / Créer) enveloppe le contenu racine. La barre est
 *    masquée pendant un flux de résultat/analyse (plein écran).
 *
 * Retour système : referme d'abord l'overlay du dessus ; sinon revient à
 * l'onglet Scanner ; sinon, depuis un écran résultat/analyse/quota/erreur,
 * revient au scanner. Au scanner racine, le système quitte l'app normalement.
 */
@Composable
fun AppNavigation(
    viewModel: ScanViewModel = viewModel(),
    onThemeChange: (ThemeChoice) -> Unit = {},
    onReplayOnboarding: () -> Unit = {},
    pendingUrl: String? = null,
    onUrlConsumed: () -> Unit = {},
    pendingTab: MainTab? = null,
    onTabConsumed: () -> Unit = {},
    openUrlAnalyzer: Boolean = false,
    onUrlAnalyzerConsumed: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val isPro by viewModel.isPro.collectAsState()
    val entitlement by viewModel.entitlement.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val scanLog = remember { ScanLogStore(context) }
    var selectedTab by remember { mutableStateOf(MainTab.SCANNER) }
    var showAbout by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showUrlAnalyzer by remember { mutableStateOf(false) }
    var productInfo by remember { mutableStateOf<GtinInfo?>(null) }
    // Passage à Pro (achat validé, essai, ou restauration) : l'écran « limite
    // journalière atteinte » n'a plus lieu d'être. On referme le paywall et, si
    // le flux Scanner est bloqué sur QuotaExhausted, on relance le scanner pour
    // que l'utilisateur puisse analyser immédiatement — sans « Réessayer » manuel.
    // remember(isPro) évite de rejouer l'effet aux simples recompositions.
    LaunchedEffect(isPro) {
        if (isPro) {
            showPaywall = false
            if (state is ScanUiState.QuotaExhausted) {
                viewModel.rescan()
            }
        }
    }
    // Les codes-barres PRODUITS (EAN/UPC) sont reconnus LOCALEMENT (fiche
    // Provenance) au lieu de lancer l'analyse de sécurité : sans quota, sans
    // réseau. Les autres codes suivent le flux d'analyse habituel.
    val handleScanned: (String, String) -> Unit = { raw, symbology ->
        val product = if (settings.productRecognitionEnabled() &&
            GtinDecoder.isProductSymbology(symbology)
        ) {
            GtinDecoder.decode(raw, symbology)
        } else {
            null
        }
        if (product != null) {
            productInfo = product
            // Journalisation locale (respecte l'interrupteur d'historique du store).
            scope.launch {
                scanLog.add(
                    ScanLogEntry(
                        value = product.gtin,
                        verdict = "NEUTRAL",
                        score = 0,
                        symbology = symbology,
                        timestamp = System.currentTimeMillis(),
                    )
                )
            }
        } else {
            viewModel.onScanned(raw, symbology)
        }
    }
    // Lien PARTAGÉ depuis une autre application (« Partager -> BlokQR ») : routé
    // vers l'analyse de sécurité (cœur de la mission). On referme les overlays et
    // on revient au Scanner pour montrer le flux d'analyse.
    LaunchedEffect(pendingUrl) {
        val url = pendingUrl?.trim().orEmpty()
        if (url.isNotEmpty()) {
            selectedTab = MainTab.SCANNER
            showAbout = false
            showSettings = false
            showHistory = false
            showPaywall = false
            showPrivacy = false
            showUrlAnalyzer = false
            productInfo = null
            viewModel.onScanned(url, "url")
            onUrlConsumed()
        }
    }
    // Raccourci d'app (appui long sur l'icône) : ouvre l'onglet demandé.
    LaunchedEffect(pendingTab) {
        pendingTab?.let {
            selectedTab = it
            onTabConsumed()
        }
    }
    // Widget « Vérifier un lien » : ouvre directement l'écran d'analyse d'URL
    // (coller un lien reçu). On referme les overlays et on revient au Scanner
    // (sous l'overlay) pour un retour cohérent.
    LaunchedEffect(openUrlAnalyzer) {
        if (openUrlAnalyzer) {
            showAbout = false
            showSettings = false
            showHistory = false
            showPaywall = false
            showPrivacy = false
            productInfo = null
            selectedTab = MainTab.SCANNER
            showUrlAnalyzer = true
            onUrlAnalyzerConsumed()
        }
    }
    // --- Retour système ------------------------------------------------------
    val backActive = productInfo != null || showPrivacy || showHistory || showAbout ||
        showSettings || showPaywall || showUrlAnalyzer ||
        selectedTab != MainTab.SCANNER ||
        state !is ScanUiState.Scanning
    BackHandler(enabled = backActive) {
        when {
            productInfo != null -> productInfo = null
            showPrivacy -> showPrivacy = false
            showAbout -> showAbout = false
            showHistory -> showHistory = false
            showSettings -> showSettings = false
            showPaywall -> showPaywall = false
            showUrlAnalyzer -> showUrlAnalyzer = false
            selectedTab != MainTab.SCANNER -> selectedTab = MainTab.SCANNER
            else -> viewModel.rescan() // Done / Analyzing / QuotaExhausted / Error -> scanner
        }
    }
    // Overlays prioritaires (la confidentialité passe AU-DESSUS de l'écran Infos).
    when {
        productInfo != null -> {
            ProductProvenanceCard(
                info = productInfo!!,
                onClose = { productInfo = null },
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
                informational = true,
            )
            return
        }
        showPrivacy -> {
            PrivacyPolicyScreen(onClose = { showPrivacy = false })
            return
        }
        showAbout -> {
            AboutScreen(
                onClose = { showAbout = false },
                onReplayOnboarding = onReplayOnboarding,
                onShowPrivacy = { showPrivacy = true },
            )
            return
        }
        showHistory -> {
            val security = remember { SecurityStore(context) }
            BiometricGate(
                enabled = security.isHistoryLockEnabled(),
                title = stringResource(R.string.history_lock_title),
                subtitle = stringResource(R.string.history_lock_subtitle),
                unlockLabel = stringResource(R.string.history_unlock),
                cancelLabel = stringResource(R.string.action_back),
                onCancel = { showHistory = false },
            ) {
                ScanHistoryScreen(
                    onClose = { showHistory = false },
                    onReplay = { value, symbology ->
                        showHistory = false
                        showSettings = false
                        val product = if (settings.productRecognitionEnabled() &&
                            GtinDecoder.isProductSymbology(symbology)
                        ) {
                            GtinDecoder.decode(value, symbology)
                        } else {
                            null
                        }
                        if (product != null) productInfo = product
                        else viewModel.onScanned(value, "url")
                    },
                )
            }
            return
        }
        showSettings -> {
            SettingsScreen(
                onClose = { showSettings = false },
                onThemeChange = onThemeChange,
                onShowHistory = { showHistory = true },
            )
            return
        }
        showPaywall -> {
            PaywallScreen(onClose = { showPaywall = false })
            return
        }
        showUrlAnalyzer -> {
            AnalyzeUrlScreen(
                onAnalyze = { url ->
                    showUrlAnalyzer = false
                    viewModel.onScanned(url, "url")
                },
                onClose = { showUrlAnalyzer = false },
            )
            return
        }
    }
    // --- Contenu racine : Scaffold + barre d'onglets -------------------------
    // La barre est masquée pendant un flux plein écran de l'onglet Scanner
    // (analyse / résultat / quota / erreur), pour garder ces écrans épurés.
    val showBar = when (selectedTab) {
        MainTab.SCANNER -> state is ScanUiState.Scanning
        else -> true
    }
    Scaffold(
        bottomBar = {
            if (showBar) {
                MainBottomBar(selected = selectedTab, onSelect = { selectedTab = it })
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            MainTab.SCANNER ->
                Box(Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding())) {
                    ScannerFlow(
                        state = state,
                        isPro = isPro,
                        entitlement = entitlement,
                        onScanned = handleScanned,
                        onShowInfo = { showAbout = true },
                        onShowSettings = { showSettings = true },
                        onUpgrade = { showPaywall = true },
                        onAnalyzeUrl = { showUrlAnalyzer = true },
                        onRescan = viewModel::rescan,
                        onContinueLocal = viewModel::continueWithLocalAnalysis,
                        onOpenSandbox = { url, risky ->
                            context.startActivity(
                                SandboxActivity.intent(context, url, riskyDownloads = risky)
                            )
                        },
                    )
                }
            MainTab.CREATE ->
                Box(Modifier.fillMaxSize().padding(innerPadding)) { CreateScreen() }
        }
    }
}
/**
 * Flux de l'onglet Scanner : scanner en direct + écrans résultat/analyse pilotés
 * par ScanUiState. Extrait pour garder AppNavigation lisible.
 */
@Composable
private fun ScannerFlow(
    state: ScanUiState,
    isPro: Boolean,
    entitlement: EntitlementUiState,
    onScanned: (String, String) -> Unit,
    onShowInfo: () -> Unit,
    onShowSettings: () -> Unit,
    onUpgrade: () -> Unit,
    onAnalyzeUrl: () -> Unit,
    onRescan: () -> Unit,
    onContinueLocal: () -> Unit,
    onOpenSandbox: (String, Boolean) -> Unit,
) {
    when (state) {
        is ScanUiState.Scanning ->
            ScannerScreen(
                onScanned = onScanned,
                onShowInfo = onShowInfo,
                onShowSettings = onShowSettings,
                entitlement = entitlement,
                onUpgrade = onUpgrade,
                onAnalyzeUrl = onAnalyzeUrl,
            )
        is ScanUiState.Analyzing ->
            AnalyzingScreen(rawPreview = state.rawPreview, retrying = state.retrying)
        is ScanUiState.Done ->
            ResultScreen(
                result = state.result,
                aiAssessment = state.aiAssessment,
                onOpenSandbox = { url ->
                    // Téléchargement TOUJOURS possible (avec confirmation) ; on
                    // signale seulement les verdicts non fiables. SAFE et NEUTRAL
                    // (ALLOWED_WITH_LIGHT_WARNING) ne sont pas « à risque ».
                    val risky =
                        state.result.verdict.opening != OpeningPolicy.ALLOWED_WITH_LIGHT_WARNING
                    onOpenSandbox(url, risky)
                },
                onRescan = onRescan,
                deepening = state.deepening,
                isPro = isPro,
                onUpgrade = onUpgrade,
                localMode = state.localMode,
                localReason = state.localReason,
                deepPreviewOffered = state.deepPreviewOffered,
            )
        is ScanUiState.QuotaExhausted ->
            QuotaExhaustedScreen(
                snapshot = state.snapshot,
                onUpgrade = onUpgrade,
                onContinueLocal = onContinueLocal,
                onClose = onRescan,
            )
        is ScanUiState.Error ->
            ErrorScreen(message = state.message, onRetry = onRescan)
    }
}