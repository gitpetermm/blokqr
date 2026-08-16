package com.blokqr.app.ui.paywall
import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.ProductDetails
import com.blokqr.app.BlokQrApp
import com.blokqr.app.billing.BlokQrBilling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
/**
 * ViewModel de l'écran Pro : connexion Billing, récupération des prix, lancement
 * de l'achat, et confirmation côté serveur (via EntitlementManager.onPurchase).
 *
 * RÉSOLUTION PAR PÉRIODE : les base plans mensuel et annuel sont résolus par
 * leur période de facturation ("P1M"/"P1Y") via BlokQrBilling.resolveBasePlanId,
 * et non par un ID codé en dur. Un changement de plan tarifaire dans la Play
 * Console (ex. remplacement de `pro-annual` par `pro-annual-2`) est donc adopté
 * automatiquement, sans mise à jour de l'application. Les IDs résolus sont
 * mémorisés pour l'achat et le calcul de fin d'essai.
 *
 * Détection de l'essai gratuit : Google ne renvoie l'offre promotionnelle (essai)
 * dans `subscriptionOfferDetails` QUE si le compte y est éligible. Donc
 * `monthlyTrialDays` / `annualTrialDays` valent automatiquement `null` quand
 * l'essai ne s'applique pas (offre inactive, compte déjà utilisé, pays exclu…).
 *
 * Lors d'un achat, on calcule la FIN d'essai (epoch) à partir du base plan
 * réellement acheté et on la transmet à EntitlementManager.onPurchase : c'est ce
 * qui alimente le badge « Essai · Xj » et le fait persister aux redémarrages.
 *
 * `isPro` est exposé pour que l'écran masque les CTA d'achat lorsqu'un abonnement
 * est déjà actif et n'affiche que le statut + « Gérer l'abonnement ».
 */
class PaywallViewModel(app: Application) : AndroidViewModel(app) {
    private val blok = app as BlokQrApp
    private val billing = blok.billing
    private val entitlementManager = blok.entitlementManager
    /** Statut Pro courant : sert à masquer les CTA d'achat si déjà abonné. */
    val isPro: StateFlow<Boolean> = entitlementManager.isPro
    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val monthlyPrice: String?,
            val annualPrice: String?,
            val monthlyTrialDays: Int?,
            val annualTrialDays: Int?,
        ) : UiState
        data object Purchasing : UiState
        data object Success : UiState
        data class Error(val message: String) : UiState
    }
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var product: ProductDetails? = null
    // IDs de base plans RÉSOLUS par période au chargement (null si la période
    // n'a aucun plan actif — le CTA correspondant affiche alors "—" et l'achat
    // est ignoré).
    @Volatile private var monthlyPlanId: String? = null
    @Volatile private var annualPlanId: String? = null
    // Base plan en cours d'achat : permet, à la confirmation (Success), de
    // calculer la fin d'essai depuis la bonne offre (mensuelle vs annuelle).
    @Volatile private var pendingBasePlanId: String? = null
    init {
        viewModelScope.launch { collectPurchases() }
        viewModelScope.launch { load() }
    }
    private suspend fun load() {
        if (!billing.connect()) {
            _state.value = UiState.Error("Service de paiement indisponible.")
            return
        }
        val pd = billing.queryProProduct()
        if (pd == null) {
            _state.value = UiState.Error("Abonnement indisponible pour le moment.")
            return
        }
        product = pd
        // Résolution des plans par période de facturation (voir BlokQrBilling).
        monthlyPlanId = billing.resolveBasePlanId(pd, BlokQrBilling.PERIOD_MONTHLY)
        annualPlanId = billing.resolveBasePlanId(pd, BlokQrBilling.PERIOD_ANNUAL)
        _state.value = ready(pd)
        // Restauration silencieuse d'un abonnement déjà actif (autre appareil…),
        // SANS écraser un éventuel statut Essai déjà restauré au démarrage.
        if (!entitlementManager.isPro.value) {
            billing.queryActiveSubscription()?.let {
                entitlementManager.onPurchase(it.purchaseToken)
            }
        }
    }
    private fun ready(pd: ProductDetails) = UiState.Ready(
        monthlyPrice = monthlyPlanId?.let { billing.recurringPrice(pd, it) },
        annualPrice = annualPlanId?.let { billing.recurringPrice(pd, it) },
        monthlyTrialDays = monthlyPlanId?.let { billing.trialDays(pd, it) },
        annualTrialDays = annualPlanId?.let { billing.trialDays(pd, it) },
    )
    private suspend fun collectPurchases() {
        billing.events.collect { ev ->
            when (ev) {
                is BlokQrBilling.PurchaseResult.Success -> {
                    _state.value = UiState.Purchasing
                    // Fin d'essai calculée depuis le base plan réellement acheté
                    // (0 si pas d'essai / client non éligible).
                    val pd = product
                    val basePlan = pendingBasePlanId
                    val trialEnd = if (pd != null && basePlan != null) {
                        billing.trialEndEpoch(pd, basePlan)
                    } else {
                        0L
                    }
                    val pro = entitlementManager.onPurchase(ev.purchaseToken, trialEnd)
                    _state.value = if (pro) UiState.Success
                    else UiState.Error("Achat non confirmé par le serveur.")
                }
                BlokQrBilling.PurchaseResult.Cancelled ->
                    product?.let { _state.value = ready(it) }
                BlokQrBilling.PurchaseResult.Pending ->
                    _state.value = UiState.Error("Paiement en attente de confirmation.")
                is BlokQrBilling.PurchaseResult.Error ->
                    _state.value = UiState.Error(ev.message)
            }
        }
    }
    fun purchaseMonthly(activity: Activity) {
        monthlyPlanId?.let { launch(activity, it) }
    }
    fun purchaseAnnual(activity: Activity) {
        annualPlanId?.let { launch(activity, it) }
    }
    private fun launch(activity: Activity, basePlanId: String) {
        val pd = product ?: return
        pendingBasePlanId = basePlanId
        billing.launchPurchase(activity, pd, basePlanId)
    }
}