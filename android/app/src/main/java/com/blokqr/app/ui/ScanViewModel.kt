package com.blokqr.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blokqr.app.ai.PhishingClassifier
import com.blokqr.app.data.GeoProvider
import com.blokqr.app.data.ScanHistoryStore
import com.blokqr.app.model.VerifiedResult
import com.blokqr.app.net.BlokQrApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** États successifs de l'écran. */
sealed interface ScanUiState {
    data object Scanning : ScanUiState
    data class Analyzing(val rawPreview: String) : ScanUiState
    data class Done(
        val result: VerifiedResult,
        val aiAssessment: PhishingClassifier.Assessment?,
    ) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val api = BlokQrApi()
    private val history = ScanHistoryStore(app)
    private val geo = GeoProvider(app)
    private val classifier = PhishingClassifier(app)

    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Scanning)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    fun onScanned(raw: String, symbology: String) {
        if (_state.value !is ScanUiState.Scanning) return
        _state.value = ScanUiState.Analyzing(raw.take(80))

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val prior = history.priorDestinationHash(raw)
                    val coarse = geo.coarseGeohash()
                    val r = api.analyze(raw, symbology, prior, coarse)
                    history.remember(raw, r.report.currentDestinationHash)
                    r
                }
                // IA embarquée sur la capture renvoyée (jamais transmise à un tiers).
                val ai = withContext(Dispatchers.Default) {
                    classifier.assess(result.report.screenshotB64)
                }
                _state.value = ScanUiState.Done(result, ai)
            } catch (e: Exception) {
                _state.value = ScanUiState.Error(e.message ?: "Échec de l'analyse.")
            }
        }
    }

    fun rescan() { _state.value = ScanUiState.Scanning }
}
