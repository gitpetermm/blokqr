package com.blokqr.app.ui.history
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blokqr.app.data.ScanLogEntry
import com.blokqr.app.data.ScanLogStore
import com.blokqr.app.ui.provenance.GtinDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
/**
 * ViewModel de l'écran Historique des scans. Lit la liste LOCALE depuis
 * ScanLogStore, applique une RECHERCHE et un FILTRE (par catégorie de verdict),
 * et permet de supprimer une entrée ou de tout effacer. Aucune donnée ne quitte
 * l'appareil.
 *
 * `entries` = liste complète (source), `visible` = liste après recherche/filtre
 * (celle que l'écran affiche). Les deux sont dérivées de la même source pour
 * garder l'état cohérent après suppression.
 */
class ScanHistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ScanLogStore(app)
    /** Catégories de filtre proposées à l'utilisateur. */
    enum class Filter { ALL, SAFE, SUSPECT, DANGEROUS, PRODUCT }
    private val _entries = MutableStateFlow<List<ScanLogEntry>>(emptyList())
    val entries: StateFlow<List<ScanLogEntry>> = _entries.asStateFlow()
    private val _visible = MutableStateFlow<List<ScanLogEntry>>(emptyList())
    /** Liste effectivement affichée (après recherche + filtre). */
    val visible: StateFlow<List<ScanLogEntry>> = _visible.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()
    // Pas de reload() au init : l'écran déclenche reload() à chaque affichage
    // (LaunchedEffect), pour refléter les scans réalisés depuis la dernière
    // ouverture sans devoir redémarrer l'application.
    fun reload() {
        viewModelScope.launch {
            _loading.value = true
            _entries.value = store.list()
            recompute()
            _loading.value = false
        }
    }
    /** Met à jour la requête de recherche (filtrage local, insensible à la casse). */
    fun setQuery(q: String) {
        _query.value = q
        recompute()
    }
    /** Change le filtre de catégorie. */
    fun setFilter(f: Filter) {
        _filter.value = f
        recompute()
    }
    /** Supprime une entrée (identité timestamp+valeur), puis rafraîchit la vue. */
    fun remove(entry: ScanLogEntry) {
        viewModelScope.launch {
            store.remove(entry.timestamp, entry.value)
            _entries.value = _entries.value.filterNot {
                it.timestamp == entry.timestamp && it.value == entry.value
            }
            recompute()
        }
    }
    fun clear() {
        viewModelScope.launch {
            store.clear()
            _entries.value = emptyList()
            recompute()
        }
    }
    /** Recalcule la liste visible à partir de la source + recherche + filtre. */
    private fun recompute() {
        val q = _query.value.trim()
        val f = _filter.value
        _visible.value = _entries.value.asSequence()
            .filter { matchesFilter(it, f) }
            .filter { q.isEmpty() || it.value.contains(q, ignoreCase = true) }
            .toList()
    }
    /**
     * Correspondance à une catégorie de filtre. On regroupe les verdicts serveur
     * en catégories lisibles ; les codes-barres produits (symbologie EAN/UPC)
     * forment une catégorie propre, quel que soit leur verdict stocké (NEUTRAL).
     */
    private fun matchesFilter(e: ScanLogEntry, f: Filter): Boolean {
        val isProduct = GtinDecoder.isProductSymbology(e.symbology)
        return when (f) {
            Filter.ALL -> true
            Filter.PRODUCT -> isProduct
            Filter.SAFE -> !isProduct && (e.verdict == "SAFE" || e.verdict == "NEUTRAL")
            Filter.SUSPECT -> !isProduct && e.verdict == "UNKNOWN"
            Filter.DANGEROUS -> !isProduct &&
                (e.verdict == "DANGEROUS" || e.verdict == "MALICIOUS")
        }
    }
}