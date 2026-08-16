package com.blokqr.app.ui
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blokqr.app.BlokQrApp
import com.blokqr.app.ai.PhishingClassifier
import com.blokqr.app.analyzer.LocalAnalyzer
import com.blokqr.app.data.ScanHistoryStore
import com.blokqr.app.data.ScanLogEntry
import com.blokqr.app.data.ScanLogStore
import com.blokqr.app.model.Verdict
import com.blokqr.app.model.VerifiedResult
import com.blokqr.app.model.LocalReason
import com.blokqr.app.net.ExceptionClassifier
import com.blokqr.app.net.BlokQrApi
import com.blokqr.app.net.AnalysisException
import com.blokqr.app.security.QuotaManager
import com.blokqr.app.security.QuotaSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.blokqr.app.billing.EntitlementUiState
/** États successifs de l'écran. */
sealed interface ScanUiState {
    data object Scanning : ScanUiState
    data class Analyzing(val rawPreview: String, val retrying: Boolean = false) : ScanUiState
    data class Done(
        val result: VerifiedResult,
        val aiAssessment: PhishingClassifier.Assessment?,
        val deepening: Boolean = false,
        /** Vrai si ce résultat provient de l'analyse LOCALE dégradée (non signée). */
        val localMode: Boolean = false,
        /** Raison de la bascule en local (hors-ligne, init, serveur, quota). */
        val localReason: LocalReason? = null,
		val deepPreviewOffered: Boolean = false,
    ) : ScanUiState
    /** Quota quotidien épuisé : écran plein affiché au PREMIER dépassement. */
    data class QuotaExhausted(val snapshot: QuotaSnapshot) : ScanUiState
    data class Error(val message: String) : ScanUiState
}
class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val blok = app as BlokQrApp
    private val api = blok.api
    private val entitlementManager = blok.entitlementManager
    private val history = ScanHistoryStore(app)
    private val scanLog = ScanLogStore(app)
    private val classifier = PhishingClassifier(app)
    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Scanning)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    /** Statut Pro (gating UI uniquement ; l'accès réel est imposé côté serveur). */
    val isPro: StateFlow<Boolean> = entitlementManager.isPro
    /** État d'abonnement pour le badge (Free / Essai · Xj / Pro). */
    val entitlement: StateFlow<EntitlementUiState> = entitlementManager.ui
    // Contexte du dernier scan : permet de relancer l'analyse profonde si
    // l'utilisateur passe Pro alors qu'un résultat rapide est affiché (achat
    // effectué DEPUIS l'écran de résultat, depuis le badge, ou restauration).
    private data class LastScan(
        val raw: String,
        val symbology: String,
        val fast: VerifiedResult,
        val ai: PhishingClassifier.Assessment?,
    )
    @Volatile private var lastScan: LastScan? = null
    @Volatile private var deepRequested = false
    // Garde-fou anti-boucle : on ne tente la restauration Pro + retry du deep
    // qu'UNE seule fois par scan sur un 402 pro_required. Remis a false a chaque
    // nouveau scan (onScanned) pour qu'un scan suivant puisse a nouveau tenter.
    @Volatile private var deepProRetryDone = false
    // Mémoire du dernier scan déclencheur d'un dépassement de quota : sert si
    // l'utilisateur choisit « continuer en local » depuis l'écran plein.
    @Volatile private var pendingLocalRaw: String? = null
    @Volatile private var pendingLocalSymbology: String? = null
    // Une fois le plein écran « quota atteint » montré une fois, les scans
    // suivants basculent directement en local (UX progressive, décision C).
    @Volatile private var quotaScreenShownToday = false
    init {
        // Restauration du statut Pro au démarrage. On fournit à restore() un
        // accès à l'abonnement actif détenu sur Google Play : si le jeton signé
        // en cache a expiré (TTL court), restore() redétecte l'abo et revérifie
        // côté serveur pour obtenir un jeton frais -> le badge Pro ne retombe
        // plus en Free à chaque redémarrage de l'app.
        viewModelScope.launch {
            entitlementManager.restore {
                if (blok.billing.connect()) {
                    blok.billing.queryActiveSubscription()?.purchaseToken
                } else {
                    null
                }
            }
        }
        // Devenir Pro (achat / restauration) pendant qu'un résultat rapide est
        // affiché => on approfondit automatiquement, sans nouveau scan.
        viewModelScope.launch {
            entitlementManager.isPro.collect { pro ->
                if (pro) deepenCurrentIfNeeded()
            }
        }
    }
    fun onScanned(raw: String, symbology: String) {
        if (_state.value !is ScanUiState.Scanning) return
        _state.value = ScanUiState.Analyzing(raw.take(80))
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val prior = history.priorDestinationHash(raw)
                    // Jeton Pro signé en cache (null si Free). Joint à l'analyse
                    // rapide : permet au serveur d'AUTO-RÉPARER le statut Pro côté
                    // quota dès ce scan (badge Pro ⇔ quota Pro restent synchrones,
                    // y compris après une réinstallation). Lu une seule fois et
                    // réutilisé pour l'éventuel retry.
                    val ent = entitlementManager.entitlementToken()
                    var r = api.analyze(raw, symbology, prior, entitlement = ent)
                    // Retry transparent UNIQUE sur verdict prudent TRANSITOIRE
                    // (timeout / redirection non résolue). Le 2e essai profite des
                    // connexions/DNS réchauffés côté serveur. On NE relance PAS un
                    // privacy_hold (lien capability sans consentement => identique).
                    if (r.verdict == Verdict.UNKNOWN && !r.report.privacyHold) {
                        _state.value = ScanUiState.Analyzing(raw.take(80), retrying = true)
                        delay(RETRY_DELAY_MS)
                        val retry = api.analyze(raw, symbology, prior, entitlement = ent)
                        if (retry.verdict != Verdict.UNKNOWN) r = retry
                    }
                    history.remember(raw, r.report.currentDestinationHash)
                    r
                }
                // IA embarquée sur la capture renvoyée (jamais transmise à un tiers).
                val ai = withContext(Dispatchers.Default) {
                    classifier.assess(result.report.screenshotB64)
                }
                _state.value = ScanUiState.Done(result, ai)
                recordHistory(result, symbology)
                // Mémorise le contexte et autorise un éventuel re-deepen post-achat.
                lastScan = LastScan(raw, symbology, result, ai)
                deepRequested = false
                deepProRetryDone = false
                // Pro -> approfondissement automatique habituel.
                // Gratuit -> tentative d'APERÇU APPROFONDI OFFERT du jour (serveur).
                if (entitlementManager.isPro.value) {
                    maybeDeepen(raw, symbology, result, ai)
                } else {
                    maybeOfferDeepPreview(raw, symbology, result, ai)
                }
            } catch (e: Exception) {
                handleScanException(e, raw, symbology)
            }
        }
    }
    /**
     * Aiguillage des exceptions d'analyse.
     *
     * 1. « quota_exceeded » (429) : premier dépassement -> écran plein ;
     *    dépassements suivants -> analyse locale directe (UX progressive).
     * 2. Phase 3 — erreurs récupérables (réseau, provisionnement, serveur
     *    transitoire) : bascule silencieuse sur l'analyse locale avec une
     *    raison contextuelle, sans jamais afficher d'erreur brute.
     * 3. Tout le reste : vraie erreur, affichée.
     */
    private suspend fun handleScanException(e: Exception, raw: String, symbology: String) {
        val msg = e.message.orEmpty()
        if (msg.startsWith("quota_exceeded")) {
            val snapshot = QuotaManager.parseQuotaExceeded(msg)
                ?: fallbackSnapshot()
            if (quotaScreenShownToday) {
                // Déjà vu le plein écran aujourd'hui -> analyse locale directe.
                runLocalAnalysis(raw, symbology, LocalReason.QUOTA_EXHAUSTED)
            } else {
                quotaScreenShownToday = true
                pendingLocalRaw = raw
                pendingLocalSymbology = symbology
                _state.value = ScanUiState.QuotaExhausted(snapshot)
            }
            return
        }
        // Phase 3 — Dégradation gracieuse : les erreurs réseau / provisionnement
        // / serveur transitoires ne doivent JAMAIS afficher une erreur brute.
        // On bascule sur l'analyse locale avec une raison contextuelle, et on
        // tente une réparation en arrière-plan pour le cas « initialisation ».
        val reason = ExceptionClassifier.classify(e)
        if (reason != null && reason != LocalReason.QUOTA_EXHAUSTED) {
            if (reason == LocalReason.INITIALIZING) {
                blok.retryProvisioning()
            }
            runLocalAnalysis(raw, symbology, reason)
            return
        }
        // Vraie erreur inattendue (bug, payload illisible…) : on l'affiche.
        _state.value = ScanUiState.Error(e.message ?: "Échec de l'analyse.")
    }
    /** Snapshot minimal si le 429 n'a pas pu être parsé (sécurité d'affichage). */
    private fun fallbackSnapshot(): QuotaSnapshot = QuotaSnapshot(
        isPro = false, limit = 7, used = 7, remaining = 0,
        resetAtIso = "", resetInSeconds = 0, recommendationPrimary = "upgrade_pro",
    )
    /**
     * Lance l'analyse LOCALE dégradée (hors-ligne, lexicale, NON signée).
     * Appelée quand le quota est épuisé et que l'utilisateur choisit de
     * continuer, ou quand une erreur récupérable bascule en local (Phase 3).
     */
    fun continueWithLocalAnalysis() {
        val raw = pendingLocalRaw ?: return
        val symbology = pendingLocalSymbology ?: ""
        viewModelScope.launch { runLocalAnalysis(raw, symbology, LocalReason.QUOTA_EXHAUSTED) }
    }
    private suspend fun runLocalAnalysis(raw: String, symbology: String, reason: LocalReason) {
        _state.value = ScanUiState.Analyzing(raw.take(80))
        val result = withContext(Dispatchers.Default) {
            LocalAnalyzer.analyze(raw, symbology)
        }
        // L'IA embarquée n'a pas de capture en local (pas de screenshot) : null.
        _state.value = ScanUiState.Done(
            result, aiAssessment = null, localMode = true, localReason = reason,
        )
		recordHistory(result, symbology)
    }
    /**
     * Pour les abonnés Pro : analyse PROFONDE asynchrone (rendu Chromium côté
     * serveur). Le verdict rapide reste affiché, puis on le met à jour avec le
     * verdict approfondi (capture + signaux dynamiques). En cas d'échec ou de
     * 402, on conserve simplement le verdict rapide.
     */
    private fun maybeDeepen(
        raw: String, symbology: String, fast: VerifiedResult,
        ai: PhishingClassifier.Assessment?,
    ) {
        if (!entitlementManager.isPro.value) return
        val token = entitlementManager.entitlementToken() ?: return
        // Inutile d'approfondir si la cible n'est pas une URL exploitable.
        if (fast.report.finalTarget == null && fast.report.originalTarget == null) return
        // Verrou anti-relance : empêche un double approfondissement quand
        // l'auto-deepen (scan) et l'observateur isPro pointent le même résultat.
        deepRequested = true
        viewModelScope.launch {
            val cur = _state.value
            if (cur is ScanUiState.Done && cur.result === fast) {
                _state.value = ScanUiState.Done(fast, ai, deepening = true)
            }
            try {
                val deep = withContext(Dispatchers.IO) {
                    val prior = history.priorDestinationHash(raw)
                    api.analyzeDeep(raw, symbology, prior, token).result
                }
                val deepAi = withContext(Dispatchers.Default) {
                    classifier.assess(deep.report.screenshotB64)
                }
                val s = _state.value
                if (s is ScanUiState.Done && s.result === fast) {
                    _state.value = ScanUiState.Done(deep, deepAi, deepening = false)
                }
            } catch (e: Exception) {
                // AUTO-REPARATION DU PRO SUR 402 pro_required.
                // Incoherence : le badge est Pro cote client, mais le serveur
                // refuse le deep pour CET install (typiquement apres un
                // reprovision qui a cree un nouvel install_id non encore marque
                // Pro cote serveur). On restaure le Pro (reverification de l'achat
                // Google -> mark_pro cote serveur, qui efface aussi revoked:),
                // puis on retente le deep UNE seule fois. Si ca echoue encore, on
                // conserve simplement le verdict rapide (comportement d'origine).
                if (e is AnalysisException &&
                    e.message == "pro_required" &&
                    entitlementManager.isPro.value &&
                    !deepProRetryDone
                ) {
                    deepProRetryDone = true
                    val repaired = tryRestoreAndRedeepen(raw, symbology, fast, ai)
                    if (repaired) return@launch
                }
                val s = _state.value
                if (s is ScanUiState.Done && s.result === fast) {
                    _state.value = ScanUiState.Done(fast, ai, deepening = false)
                }
            }
        }
    }

    /**
     * Restaure le statut Pro cote serveur puis retente l'analyse profonde UNE
     * fois. Appele quand /v1/analyze/deep renvoie 402 pro_required alors que le
     * badge est Pro (install fraichement reprovisionne, non encore marque Pro
     * cote serveur). Etapes :
     *   1. restore() : reverifie l'abonnement actif detenu sur Google Play et
     *      obtient un jeton d'entitlement FRAIS (le serveur (re)marque Pro cet
     *      install et efface toute marque revoked: residuelle).
     *   2. retente analyzeDeep avec le jeton rafraichi.
     * Renvoie true si le deep a finalement abouti (etat mis a jour), false sinon
     * (l'appelant conserve alors le verdict rapide).
     */
    private suspend fun tryRestoreAndRedeepen(
        raw: String, symbology: String, fast: VerifiedResult,
        ai: PhishingClassifier.Assessment?,
    ): Boolean {
        return try {
            entitlementManager.restore {
                if (blok.billing.connect()) {
                    blok.billing.queryActiveSubscription()?.purchaseToken
                } else {
                    null
                }
            }
            val freshToken = entitlementManager.entitlementToken() ?: return false
            val deep = withContext(Dispatchers.IO) {
                val prior = history.priorDestinationHash(raw)
                api.analyzeDeep(raw, symbology, prior, freshToken).result
            }
            val deepAi = withContext(Dispatchers.Default) {
                classifier.assess(deep.report.screenshotB64)
            }
            val s = _state.value
            if (s is ScanUiState.Done && s.result === fast) {
                _state.value = ScanUiState.Done(deep, deepAi, deepening = false)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    /**
     * Relance l'analyse profonde sur le résultat rapide actuellement affiché,
     * si et seulement si l'utilisateur est désormais Pro et qu'on ne l'a pas
     * déjà approfondi/lancé. Couvre l'achat effectué DEPUIS l'écran de résultat.
     */
    private fun deepenCurrentIfNeeded() {
        val last = lastScan ?: return
        val cur = _state.value
        if (cur !is ScanUiState.Done) return
        if (cur.result !== last.fast) return         // déjà approfondi / autre résultat
        if (cur.deepening || deepRequested) return    // déjà en cours / déjà demandé
        maybeDeepen(last.raw, last.symbology, last.fast, last.ai)
    }
	
	 /**
     * Pour les utilisateurs GRATUITS : tente l'aperçu approfondi OFFERT du jour
     * sur le PREMIER scan navigable et non bloqué. Le SERVEUR décide (compteur
     * Redis, 1/jour) : si accordé (header X-Deep-Free: granted), on remplace le
     * résultat rapide par le résultat profond, avec le badge « offert ». Si
     * l'offre est épuisée (402 pro_required) ou en cas d'échec, on conserve
     * simplement le verdict rapide (aucune dégradation).
     */
    private fun maybeOfferDeepPreview(
        raw: String, symbology: String, fast: VerifiedResult,
        ai: PhishingClassifier.Assessment?,
    ) {
        // Inutile de tenter sur un contenu non navigable ou un lien bloqué :
        // l'offre ne doit pas être « gâchée » là où l'aperçu n'a pas de valeur.
        if (!fast.isNavigable) return
        if (fast.verdict.opening == com.blokqr.app.model.OpeningPolicy.BLOCKED) return
        if (fast.report.finalTarget == null && fast.report.originalTarget == null) return
        deepRequested = true
        viewModelScope.launch {
            val cur = _state.value
            if (cur is ScanUiState.Done && cur.result === fast) {
                _state.value = ScanUiState.Done(fast, ai, deepening = true)
            }
            try {
                // entitlement = null : appel gratuit ; le serveur accorde ou 402.
                val outcome = withContext(Dispatchers.IO) {
                    val prior = history.priorDestinationHash(raw)
                    api.analyzeDeep(raw, symbology, prior, entitlement = null)
                }
                val granted = outcome.deepFree == com.blokqr.app.net.DeepFreeStatus.GRANTED
                val deepAi = withContext(Dispatchers.Default) {
                    classifier.assess(outcome.result.report.screenshotB64)
                }
                val s = _state.value
                if (s is ScanUiState.Done && s.result === fast) {
                    _state.value = ScanUiState.Done(
                        outcome.result, deepAi, deepening = false,
                        deepPreviewOffered = granted,
                    )
                }
            } catch (e: Exception) {
                // 402 (offre épuisée) ou tout échec : on garde le verdict rapide.
                val s = _state.value
                if (s is ScanUiState.Done && s.result === fast) {
                    _state.value = ScanUiState.Done(fast, ai, deepening = false)
                }
            }
        }
    }
    /**
     * Enregistre le scan dans l'historique LOCAL (si activé). Best-effort, non
     * bloquant. Valeur = destination finale (ou valeur décodée) ; verdict
     * EFFECTIF (NEUTRAL si non navigable) ; score ; symbologie ; horodatage.
     */
    private fun recordHistory(result: VerifiedResult, symbology: String) {
        val shown = if (!result.isNavigable) Verdict.NEUTRAL else result.verdict
        val entry = ScanLogEntry(
            value = result.report.finalTarget ?: result.report.displayedValue,
            verdict = shown.name,
            score = result.score,
            symbology = symbology,
            timestamp = System.currentTimeMillis(),
        )
        viewModelScope.launch { scanLog.add(entry) }
    }

    fun rescan() { _state.value = ScanUiState.Scanning }
    private companion object {
        /** Petit délai avant le retry transparent (laisse passer un pic transitoire). */
        const val RETRY_DELAY_MS = 400L
    }
}