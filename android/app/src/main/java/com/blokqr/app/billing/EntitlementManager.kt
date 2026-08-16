package com.blokqr.app.billing
import com.blokqr.app.data.EntitlementStore
import com.blokqr.app.net.BlokQrApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
/** Palier d'abonnement, pour l'affichage (badge, gating UI). */
enum class EntitlementTier { FREE, TRIAL, PRO }
/** État d'abonnement consommé par l'UI (badge). */
data class EntitlementUiState(
    val tier: EntitlementTier = EntitlementTier.FREE,
    val trialDaysLeft: Int = 0,
)
/**
 * Source de vérité du statut Pro CÔTÉ UI.
 *
 * ⚠️ Ce statut ne sert qu'à l'affichage (afficher/masquer les features Pro,
 * afficher le badge). L'accès réel à l'analyse profonde est imposé CÔTÉ SERVEUR :
 * /v1/analyze/deep vérifie l'entitlement (ou la marque Pro serveur) et renvoie
 * 402 sinon. Un contournement local de `isPro` ne donne donc aucun accès réel —
 * il ne change que l'UI.
 *
 * Flux :
 *   - restore(activePurchaseToken) au démarrage :
 *       1) cache local d'abord : badge Pro INSTANTANÉ si l'échéance court encore
 *          (hydratation optimiste, sans réseau) -> pas de clignotement Free->Pro ;
 *       2) rafraîchissement du jeton signé côté serveur ; si le jeton en cache est
 *          absent/expiré, on interroge Google Play (abonnement actif détenu) puis
 *          on revérifie côté serveur pour obtenir un jeton FRAIS ;
 *       3) on ne rétrograde en Free (et ne purge le cache) QUE si l'échéance Pro
 *          locale est réellement dépassée ; hors-ligne, continuité d'affichage.
 *   - onPurchase(token, trialUntil) après un achat / une restauration :
 *     vérifie côté serveur, met en cache l'entitlement signé, passe Pro/Essai.
 */
class EntitlementManager(
    private val api: BlokQrApi,
    private val store: EntitlementStore,
) {
    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()
    /** État d'abonnement pour l'UI (badge Free / Essai · Xj / Pro). */
    private val _ui = MutableStateFlow(EntitlementUiState())
    val ui: StateFlow<EntitlementUiState> = _ui.asStateFlow()
    @Volatile private var cachedToken: String? = null
    @Volatile private var proUntil: Long = 0L
    @Volatile private var trialUntil: Long = 0L
    /** Jeton signé courant (à présenter sur /v1/analyze/deep), ou null. */
    fun entitlementToken(): String? = cachedToken
    /**
     * Au démarrage : restaure le statut Pro.
     *
     * Ordre :
     *   1. HYDRATATION OPTIMISTE : si l'échéance Pro en cache court encore
     *      (now < proUntil), on affiche Pro IMMÉDIATEMENT, sans aucun appel
     *      réseau (badge instantané, robuste hors-ligne). C'est ce qui supprime
     *      le clignotement Free -> Pro au redémarrage.
     *   2. Rafraîchissement du jeton signé : on revérifie le jeton en cache
     *      (verifyEntitlementToken) pour disposer d'un jeton frais à présenter à
     *      /v1/analyze/deep. Hors-ligne, on conserve l'affichage selon la
     *      dernière échéance vérifiée.
     *   3. Si le jeton signé est absent/périmé, on interroge Google Play via
     *      [activePurchaseToken] : un abonnement actif est revérifié côté serveur
     *      (verifyPurchase) -> jeton FRAIS + échéance à jour.
     *   4. On ne rétrograde en Free (et ne purge le cache) QUE si l'échéance Pro
     *      locale est réellement dépassée (now >= proUntil). Une résiliation
     *      laisse donc l'accès actif jusqu'à la fin de la période en cours.
     *
     * @param activePurchaseToken fournisseur (suspend) renvoyant le purchaseToken
     *        de l'abonnement actif détenu (via BillingClient.queryPurchases), ou
     *        null. Injecté par la couche qui possède le BillingClient
     *        (BlokQrBilling). Si null (ex. tests unitaires), seuls le cache et son
     *        rafraîchissement réseau sont utilisés.
     */
    suspend fun restore(
        activePurchaseToken: (suspend () -> String?)? = null,
    ) = withContext(Dispatchers.IO) {
        val cached = store.load()
        val now = System.currentTimeMillis() / 1000

        if (cached != null) {
            cachedToken = cached.token
            proUntil = cached.proUntil
            trialUntil = cached.trialUntil

            // 1) HYDRATATION OPTIMISTE : si la dernière échéance Pro vérifiée court
            //    encore, badge Pro IMMÉDIAT (lecture cache seule, sans réseau).
            //    Supprime le clignotement Free -> Pro au redémarrage. Le serveur
            //    reste l'arbitre réel (/v1/analyze/deep).
            if (now < cached.proUntil) {
                set(true, cached.token)
            }

            // 2) Rafraîchissement du jeton signé (présenté à /v1/analyze/deep).
            //    On ne rétrograde PAS le badge ici tant que la période court.
            try {
                val res = api.verifyEntitlementToken(cached.token)
                if (res.isProActive) {
                    set(true, cached.token)
                    return@withContext
                }
                // Jeton signé périmé : on tente un rafraîchissement Play ci-dessous.
            } catch (e: Exception) {
                // Hors-ligne / manifeste indisponible : on conserve l'affichage
                // selon la dernière échéance Pro vérifiée.
                val stillValid = now < cached.proUntil
                set(stillValid, if (stillValid) cached.token else null)
                return@withContext
            }
        }

        // 3) Jeton signé absent/périmé : revérifier l'abonnement actif détenu via
        //    Google Play -> jeton FRAIS + échéance à jour (prend en compte les
        //    renouvellements au redémarrage suivant).
        val token = try {
            activePurchaseToken?.invoke()
        } catch (e: Exception) {
            null
        }
        if (token != null) {
            val granted = onPurchase(token, trialUntil)
            if (granted) return@withContext
        }

        // 4) Aucun abonnement actif confirmé. On ne rétrograde en Free (et ne purge
        //    le cache) QUE si l'échéance Pro locale est réellement dépassée. Tant
        //    que now < proUntil, on conserve l'affichage Pro optimiste (résilience
        //    hors-ligne ; sémantique « résiliation = accès jusqu'à fin de période »).
        if (now >= proUntil) {
            proUntil = 0L
            trialUntil = 0L
            set(false, null)
            if (cached != null) store.clear()
        }
    }
    /**
     * Après un achat (ou une restauration d'abonnement) : vérifie auprès du
     * serveur, met en cache l'entitlement signé. `trialUntilEpoch` (optionnel)
     * porte la fin de période d'essai si l'achat est en phase d'essai gratuit
     * (renseigné par la couche facturation). Renvoie true si Pro accordé.
     */
    suspend fun onPurchase(purchaseToken: String, trialUntilEpoch: Long = 0L): Boolean =
        withContext(Dispatchers.IO) {
            val resp = try {
                api.verifyPurchase(purchaseToken)
            } catch (e: Exception) {
                return@withContext false
            }
            val token = resp.entitlement
            if (!resp.pro || token == null) {
                false
            } else {
                val until = parseEpoch(resp.expiry)
                    ?: (System.currentTimeMillis() / 1000 + 86_400)
                store.save(token, until, trialUntilEpoch)
                proUntil = until
                trialUntil = trialUntilEpoch
                set(true, token)
                true
            }
        }
    /** Oubli local (déconnexion / abonnement perdu). */
    suspend fun forget() = withContext(Dispatchers.IO) {
        proUntil = 0L; trialUntil = 0L
        store.clear()
        set(false, null)
    }
    private fun set(pro: Boolean, token: String?) {
        cachedToken = token
        _isPro.value = pro
        recomputeUi()
    }
    private fun recomputeUi() {
        _ui.value = computeUi(
            isPro = _isPro.value,
            trialUntilEpoch = trialUntil,
            nowEpoch = System.currentTimeMillis() / 1000,
        )
    }
    private fun parseEpoch(iso: String?): Long? = try {
        if (iso.isNullOrBlank()) null
        else java.time.OffsetDateTime.parse(iso).toEpochSecond()
    } catch (e: Exception) {
        null
    }
    companion object {
        /**
         * Calcul PUR (donc testable hors Android) de l'état badge à partir du
         * statut Pro, de la fin d'essai (epoch s) et de l'instant courant
         * (epoch s) :
         *   - FREE si non Pro ;
         *   - TRIAL si l'essai court encore (jours restants arrondis AU SUPÉRIEUR,
         *     minimum 1) ;
         *   - PRO sinon (Pro sans essai, ou essai expiré).
         */
        fun computeUi(isPro: Boolean, trialUntilEpoch: Long, nowEpoch: Long): EntitlementUiState {
            val tier = when {
                !isPro -> EntitlementTier.FREE
                trialUntilEpoch > nowEpoch -> EntitlementTier.TRIAL
                else -> EntitlementTier.PRO
            }
            val days = if (tier == EntitlementTier.TRIAL) {
                kotlin.math.ceil((trialUntilEpoch - nowEpoch) / 86_400.0).toInt().coerceAtLeast(1)
            } else {
                0
            }
            return EntitlementUiState(tier, days)
        }
    }
}