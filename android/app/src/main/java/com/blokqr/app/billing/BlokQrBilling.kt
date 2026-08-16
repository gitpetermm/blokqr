package com.blokqr.app.billing
import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
/**
 * Enveloppe Google Play Billing v8 (abonnement BlokQR Pro).
 *
 * Rôle : se connecter, récupérer l'abonnement `blokqr_pro` et ses base plans,
 * lancer l'achat, exposer le `purchaseToken` et la fin d'essai éventuelle. La
 * chaîne `purchaseToken -> /v1/billing/verify -> EntitlementVerifier ->
 * EntitlementStore` est câblée via EntitlementManager.
 *
 * SÉLECTION PAR PÉRIODE (et non par ID de base plan) : les plans sont résolus
 * par leur période de facturation récurrente ("P1M" mensuel, "P1Y" annuel) via
 * [resolveBasePlanId]. Ainsi, un nouveau plan tarifaire créé dans la Play
 * Console (ex. `pro-annual-2`) est adopté AUTOMATIQUEMENT, sans mise à jour de
 * l'application. Si plusieurs plans actifs partagent la même période (fenêtre
 * de transition tarifaire), on préfère celui qui propose un ESSAI GRATUIT au
 * client (Google ne renvoie l'offre d'essai qu'aux éligibles), puis le PRIX
 * RÉCURRENT LE PLUS BAS — c'est toujours la meilleure offre pour l'utilisateur.
 *
 * IMPORTANT : on n'ACQUITTE PAS l'achat ici. L'acquittement (obligatoire sous 3
 * jours sinon remboursement) est fait CÔTÉ SERVEUR par /v1/billing/verify, après
 * vérification auprès de Google.
 */
class BlokQrBilling(context: Context) {
    sealed interface PurchaseResult {
        /** Achat confirmé : jeton à vérifier côté serveur. */
        data class Success(val purchaseToken: String, val products: List<String>) : PurchaseResult
        /** Paiement en attente (ex. paiement différé) : ne pas accorder l'accès. */
        data object Pending : PurchaseResult
        data object Cancelled : PurchaseResult
        data class Error(val message: String) : PurchaseResult
    }
    private val _events = MutableSharedFlow<PurchaseResult>(extraBufferCapacity = 8)
    /** Flux des résultats d'achat (à collecter par la couche au-dessus). */
    val events: SharedFlow<PurchaseResult> = _events.asSharedFlow()
    private val purchaseListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                purchases?.forEach { emitPurchase(it) }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                _events.tryEmit(PurchaseResult.Cancelled)
            else ->
                _events.tryEmit(PurchaseResult.Error(result.debugMessage))
        }
    }
    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchaseListener)
        // v8 : enablePendingPurchases() sans argument a été supprimé.
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        // v8 (recommandé) : reconnexion automatique du service Play.
        .enableAutoServiceReconnection()
        .build()
    private fun emitPurchase(p: Purchase) {
        when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED ->
                _events.tryEmit(PurchaseResult.Success(p.purchaseToken, p.products))
            Purchase.PurchaseState.PENDING ->
                _events.tryEmit(PurchaseResult.Pending)
            else -> Unit
        }
    }
    /** Établit la connexion au service Play. true si prêt. */
    suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) {
                        cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                    }
                }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }
    /** Détails de l'abonnement Pro (`blokqr_pro`), ou null si indisponible. */
    suspend fun queryProProduct(): ProductDetails? = suspendCancellableCoroutine { cont ->
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        // v8 : la signature renvoie un QueryProductDetailsResult (liste récupérée
        // + liste non récupérée). On lit la liste récupérée.
        client.queryProductDetailsAsync(params) { result, queryResult ->
            val details = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                queryResult.productDetailsList.firstOrNull()
            } else null
            if (cont.isActive) cont.resume(details)
        }
    }
    // -----------------------------------------------------------------------
    // Résolution des base plans PAR PÉRIODE de facturation
    // -----------------------------------------------------------------------
    /**
     * Phase récurrente d'une offre : DERNIÈRE phase tarifaire (les phases
     * promotionnelles — essai gratuit, prix de lancement — précèdent toujours la
     * phase infinie du plan de base dans la liste renvoyée par Play).
     */
    private fun recurringPhase(
        offer: ProductDetails.SubscriptionOfferDetails,
    ): ProductDetails.PricingPhase? = offer.pricingPhases.pricingPhaseList.lastOrNull()
    /** true si au moins une offre du plan comporte une phase GRATUITE (essai). */
    private fun hasEligibleTrial(offers: List<ProductDetails.SubscriptionOfferDetails>): Boolean =
        offers.any { o ->
            o.offerId != null &&
                o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        }
    /** Prix récurrent minimal (micros) parmi les offres d'un plan. */
    private fun minRecurringMicros(
        offers: List<ProductDetails.SubscriptionOfferDetails>,
    ): Long = offers.minOfOrNull { recurringPhase(it)?.priceAmountMicros ?: Long.MAX_VALUE }
        ?: Long.MAX_VALUE
    /**
     * ID du base plan retenu pour une période donnée ("P1M" / "P1Y"), ou null si
     * aucun plan actif ne correspond. Si PLUSIEURS plans actifs partagent la
     * période (transition tarifaire) : préférence au plan offrant un essai
     * gratuit éligible, puis au prix récurrent le plus bas.
     */
    fun resolveBasePlanId(details: ProductDetails, isoPeriod: String): String? {
        val offers = details.subscriptionOfferDetails ?: return null
        val byPlan = offers
            .groupBy { it.basePlanId }
            .filterValues { plan ->
                plan.any { recurringPhase(it)?.billingPeriod == isoPeriod }
            }
        if (byPlan.isEmpty()) return null
        return byPlan.entries
            .minWithOrNull(
                compareBy(
                    { entry -> if (hasEligibleTrial(entry.value)) 0 else 1 },
                    { entry -> minRecurringMicros(entry.value) },
                )
            )
            ?.key
    }
    /**
     * Offre retenue pour un base plan : on PRÉFÈRE une offre promotionnelle (ex.
     * essai gratuit) lorsqu'elle est présente — Google ne renvoie l'offre d'essai
     * qu'aux clients ÉLIGIBLES (nouveaux abonnés). Sinon, base plan « nu ».
     */
    private fun selectedOffer(
        details: ProductDetails,
        basePlanId: String,
    ): ProductDetails.SubscriptionOfferDetails? {
        val offers = details.subscriptionOfferDetails ?: return null
        val forPlan = offers.filter { it.basePlanId == basePlanId }
        return forPlan.firstOrNull { it.offerId != null } ?: forPlan.firstOrNull()
    }
    /**
     * Jeton d'offre du base plan demandé.
     * Même logique de sélection que [selectedOffer].
     */
    fun offerToken(details: ProductDetails, basePlanId: String): String? =
        selectedOffer(details, basePlanId)?.offerToken
    /**
     * Prix récurrent formaté (hors essai) du base plan : dernière phase de
     * l'offre « nue » si présente, sinon de l'offre retenue.
     */
    fun recurringPrice(details: ProductDetails, basePlanId: String): String? {
        val offers = details.subscriptionOfferDetails ?: return null
        val bare = offers.firstOrNull { it.basePlanId == basePlanId && it.offerId == null }
        val offer = bare ?: offers.firstOrNull { it.basePlanId == basePlanId } ?: return null
        return recurringPhase(offer)?.formattedPrice
    }
    /**
     * Durée de l'essai gratuit (jours) du base plan, ou null si aucun essai
     * éligible (offre absente, compte déjà servi, pays exclu…). Une phase
     * d'essai = phase à prix nul ; durée lue en ISO-8601 ("P2W", "P14D"…).
     */
    fun trialDays(details: ProductDetails, basePlanId: String): Int? {
        val offer = details.subscriptionOfferDetails
            ?.firstOrNull { it.basePlanId == basePlanId && it.offerId != null }
            ?: return null
        val freePhase = offer.pricingPhases.pricingPhaseList
            .firstOrNull { it.priceAmountMicros == 0L }
            ?: return null
        val days = (isoPeriodToSeconds(freePhase.billingPeriod) / 86_400L).toInt()
        return if (days > 0) days else null
    }
    /**
     * Fin de l'essai gratuit (epoch, secondes) pour le base plan donné, calculée
     * depuis la phase tarifaire GRATUITE (priceAmountMicros == 0) de l'offre
     * retenue. Renvoie 0 si aucun essai (offre nue, ou client non éligible —
     * Google n'inclut alors pas de phase gratuite).
     *
     * À appeler au moment de l'achat (le `now` sert de base au compte à rebours
     * du badge). La facturation réelle reste pilotée par Google ; cette valeur ne
     * sert qu'à l'affichage (badge Essai · Xj).
     */
    fun trialEndEpoch(
        details: ProductDetails,
        basePlanId: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Long {
        val offer = selectedOffer(details, basePlanId) ?: return 0L
        val freePhase = offer.pricingPhases.pricingPhaseList
            .firstOrNull { it.priceAmountMicros == 0L } ?: return 0L
        val seconds = isoPeriodToSeconds(freePhase.billingPeriod)
        return if (seconds > 0) nowEpochSeconds + seconds else 0L
    }
    /** Lance le tunnel d'achat pour un base plan. À appeler sur le thread principal. */
    fun launchPurchase(activity: Activity, details: ProductDetails, basePlanId: String): Boolean {
        val token = offerToken(details, basePlanId) ?: return false
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(token)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = client.launchBillingFlow(activity, flowParams)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }
    /**
     * Abonnement actif déjà détenu (restauration au démarrage / changement
     * d'appareil), ou null. Le jeton renvoyé doit être (re)vérifié côté serveur.
     */
    suspend fun queryActiveSubscription(): Purchase? = suspendCancellableCoroutine { cont ->
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            val active = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            } else null
            if (cont.isActive) cont.resume(active)
        }
    }
    /** Libère la connexion (à appeler quand le composant propriétaire est détruit). */
    fun close() {
        if (client.isReady) client.endConnection()
    }
    companion object {
        /** ID d'abonnement créé dans la Play Console. */
        const val PRODUCT_ID = "blokqr_pro"
        /** Période récurrente mensuelle (format ISO-8601 renvoyé par Play). */
        const val PERIOD_MONTHLY = "P1M"
        /** Période récurrente annuelle. */
        const val PERIOD_ANNUAL = "P1Y"
        /**
         * Convertit une durée ISO-8601 telle que renvoyée par Play (ex. "P2W",
         * "P1M", "P3D", "P1Y") en secondes. Approximations d'affichage : mois = 30
         * jours, année = 365 jours (suffisant pour le compte à rebours du badge).
         * Renvoie 0 si la chaîne est vide ou non reconnue.
         */
        internal fun isoPeriodToSeconds(iso: String?): Long {
            if (iso.isNullOrBlank()) return 0L
            val m = Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?")
                .matchEntire(iso) ?: return 0L
            val (y, mo, w, d) = m.destructured
            val years = y.toLongOrNull() ?: 0L
            val months = mo.toLongOrNull() ?: 0L
            val weeks = w.toLongOrNull() ?: 0L
            val days = d.toLongOrNull() ?: 0L
            return ((years * 365) + (months * 30) + (weeks * 7) + days) * 86_400L
        }
    }
}