package com.blokqr.app
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.blokqr.app.analyzer.BlocklistManager
import com.blokqr.app.analyzer.BlocklistRefreshWorker
import com.blokqr.app.billing.BlokQrBilling
import com.blokqr.app.billing.EntitlementManager
import com.blokqr.app.data.EntitlementStore
import com.blokqr.app.data.LocaleHelper
import com.blokqr.app.data.SettingsStore
import com.blokqr.app.net.BlokQrApi
import com.blokqr.app.security.InstallProvisioningException
import com.blokqr.app.security.InstallTokenManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
class BlokQrApp : Application() {
    // Singletons applicatifs partagés.
    //
    // InstallTokenManager doit être disponible AVANT que BlokQrApi soit
    // construite (constructor injection). On le déclare donc en premier ;
    // l'ordre des `by lazy` est résolu à la demande, donc l'app ne crée
    // installTokenManager que la première fois que api (ou un appel direct)
    // y touche -- typiquement quand provisionInBackground() s'exécute.
    val installTokenManager: InstallTokenManager by lazy { InstallTokenManager(this) }
    // BlokQrApi est unique pour mutualiser le cache du manifeste (clés de
    // vérification) entre analyses et entitlement. Désormais elle reçoit le
    // installTokenManager pour permettre à HmacInterceptor de signer les requêtes.
    val api: BlokQrApi by lazy { BlokQrApi(installTokenManager) }
    val entitlementStore: EntitlementStore by lazy { EntitlementStore(this) }
    val entitlementManager: EntitlementManager by lazy {
        EntitlementManager(api, entitlementStore)
    }
    val billing: BlokQrBilling by lazy { BlokQrBilling(this) }
    // Garde-fou anti-empilement : empêche plusieurs re-provisionnements
    // concurrents si l'utilisateur enchaîne des scans alors que le serveur
    // ne reconnaît pas (encore) l'installation.
    private val provisioningInFlight = AtomicBoolean(false)
    /**
     * Applique la langue choisie au CONTEXTE APPLICATIF lui-même, et pas
     * seulement à celui de l'Activity. Sans cela, tout ce qui résout des
     * chaînes via applicationContext (WorkManager, notifications d'arrière-plan,
     * services) reste dans la langue du système -> mélange de langues à l'écran.
     */
    override fun attachBaseContext(base: Context) {
        val lang = SettingsStore(base).languageTag()
        super.attachBaseContext(LocaleHelper.wrap(base, lang))
    }
    override fun onCreate() {
        super.onCreate()
        // Le process ":sandbox" (WebView isolée, cf. AndroidManifest) ne doit PAS
        // exécuter l'init lourde : ni provisioning, ni blocklist, ni worker, ni
        // réseau. Application.onCreate() s'exécutant dans CHAQUE process, ce
        // garde-fou évite une double initialisation et toute contention sur
        // l'EncryptedSharedPreferences (install_id / secret HMAC). Les singletons
        // `by lazy` restent non initialisés tant que rien ne les touche, donc le
        // process sandbox n'instancie aucun d'eux.
        if (!isMainProcess()) return
        // Canal de notification (requis depuis Android 8 ; minSdk=29 -> toujours présent).
        // Le nom du canal est localisé : il s'affiche dans les paramètres système.
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        // Provisionnement d'installation au démarrage. Lancé en arrière-plan
        // pour ne pas bloquer le rendu de la 1re Activity. En cas d'échec
        // (réseau coupé), l'app continue : LocalAnalyzer prendra le relais et
        // un nouveau provisionnement sera tenté au prochain scan en ligne.
        provisionInBackground()
        // Paquet 8 : initialisation de la blocklist locale (3 couches bundled/
        // cached/refresh). Lancée en arrière-plan : la bundled est chargée
        // depuis assets, et la cached depuis filesDir si présente et valide.
        // LocalAnalyzer consultera ensuite BlocklistManager.contains(domain)
        // à chaque scan local.
        initializeBlocklistInBackground()
        // Planification du refresh quotidien (WorkManager 24h).
        // Idempotent : si déjà planifié, ne fait rien (KEEP policy par défaut).
        BlocklistRefreshWorker.enqueue(this)
    }
    /**
     * Déclenche un POST /v1/install si l'app n'est pas encore provisionnée.
     * Idempotent : ne fait rien si déjà fait. Exécuté hors thread principal.
     */
    private fun provisionInBackground() {
        if (!provisioningInFlight.compareAndSet(false, true)) return
        thread(name = "blokqr-install-provision", isDaemon = true) {
            try {
                installTokenManager.ensureProvisioned()
                Log.i(TAG, "Provisionnement OK ou déjà existant.")
            } catch (e: InstallProvisioningException) {
                // Pas critique : LocalAnalyzer fonctionne sans install. Une
                // nouvelle tentative aura lieu au prochain démarrage / scan.
                Log.w(TAG, "Provisionnement initial impossible (${e.reason}). Retry ultérieur.")
            } catch (e: Exception) {
                Log.w(TAG, "Provisionnement initial : erreur inattendue.", e)
            } finally {
                provisioningInFlight.set(false)
            }
        }
    }
    /**
     * Tente de RÉPARER l'installation en arrière-plan, appelé par le ViewModel
     * quand un scan échoue avec une erreur de provisionnement / 401
     * install_unknown. Non bloquant et idempotent (garde-fou anti-empilement).
     *
     * - Si une installation locale existe déjà mais que le serveur la rejette
     *   (éviction LRU côté serveur), on `reprovision()` : état propre + nouveau
     *   token Play Integrity (le quota du jour repart à zéro, comportement
     *   attendu pour une nouvelle installation).
     * - Si aucune installation n'existe encore (réseau coupé au démarrage),
     *   un simple `ensureProvisioned()` suffit.
     *
     * Le résultat n'est pas attendu : le scan courant continue en local. Le
     * PROCHAIN scan en ligne profitera de l'installation réparée.
     */
    fun retryProvisioning() {
        if (!provisioningInFlight.compareAndSet(false, true)) return
        thread(name = "blokqr-install-retry", isDaemon = true) {
            try {
                if (installTokenManager.isProvisioned()) {
                    installTokenManager.reprovision()
                } else {
                    installTokenManager.ensureProvisioned()
                }
                Log.i(TAG, "Re-provisionnement déclenché : OK.")
            } catch (e: InstallProvisioningException) {
                Log.w(TAG, "Re-provisionnement impossible (${e.reason}).")
            } catch (e: Exception) {
                Log.w(TAG, "Re-provisionnement : erreur inattendue.", e)
            } finally {
                provisioningInFlight.set(false)
            }
        }
    }
    /**
     * Charge la blocklist locale (bundled depuis assets + cached depuis
     * filesDir si présent). Le provider de clés est lazy : le manifeste de
     * confiance peut ne pas être encore fetché au moment de l'init, c'est OK
     * — la signature de la blocklist sera quand même vérifiée contre les
     * clés publiques embarquées dans le payload (Ed25519 + ML-DSA-65).
     * La validation key_id contre le manifeste interviendra plus tard,
     * quand l'app aura besoin de re-vérifier (refresh worker, par exemple).
     */
    private fun initializeBlocklistInBackground() {
        thread(name = "blokqr-blocklist-init", isDaemon = true) {
            try {
                BlocklistManager.initializeBlocking(
                    context = this@BlokQrApp,
                    trustedKeysProvider = null,  // bootstrap : pas encore de manifeste
                )
                Log.i(TAG, "Blocklist initialisée : ${BlocklistManager.currentInfo()}")
            } catch (e: Exception) {
                Log.w(TAG, "Initialisation blocklist échouée (non bloquant)", e)
            }
        }
    }
    /**
     * Vrai si l'on s'exécute dans le process principal (et non dans un process
     * secondaire comme ":sandbox"). Application.onCreate() s'exécute dans CHAQUE
     * process ; ce garde-fou réserve l'init lourde au process principal.
     *
     * En cas d'impossibilité d'obtenir le nom du process (rare), on suppose le
     * process principal : on ne casse jamais l'init normale de l'app.
     */
    private fun isMainProcess(): Boolean {
        val pid = android.os.Process.myPid()
        val am = getSystemService(android.app.ActivityManager::class.java)
        val procName = am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        return procName == null || procName == packageName
    }
    companion object {
        private const val TAG = "BlokQrApp"
        const val CHANNEL_ID = "qr_analysis"
    }
}