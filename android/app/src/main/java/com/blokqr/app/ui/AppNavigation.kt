package com.blokqr.app.ui

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blokqr.app.ui.analyze.AnalyzingScreen
import com.blokqr.app.ui.result.ResultScreen
import com.blokqr.app.ui.sandbox.SandboxBrowserScreen
import com.blokqr.app.ui.scan.ScannerScreen

/**
 * Aiguillage de l'interface, piloté par l'état du ViewModel.
 * Un état local gère l'ouverture du navigateur isolé par-dessus le résultat.
 */
@Composable
fun AppNavigation(viewModel: ScanViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var sandboxUrl by remember { mutableStateOf<String?>(null) }

    val current = sandboxUrl
    if (current != null) {
        SandboxBrowserScreen(url = current, onClose = { sandboxUrl = null })
        return
    }

    when (val s = state) {
        is ScanUiState.Scanning ->
            ScannerScreen(onScanned = viewModel::onScanned)

        is ScanUiState.Analyzing ->
            AnalyzingScreen(rawPreview = s.rawPreview)

        is ScanUiState.Done ->
            ResultScreen(
                result = s.result,
                aiAssessment = s.aiAssessment,
                onOpenSandbox = { sandboxUrl = it },
                onReport = { /* envoi communautaire consenti — hors périmètre v1 */ },
                onRescan = viewModel::rescan,
            )

        is ScanUiState.Error ->
            ErrorScreen(message = s.message, onRetry = viewModel::rescan)
    }
}
