package com.blokqr.app.analyzer

import android.content.Context
import android.util.Log
import com.blokqr.app.crypto.KeyManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Gestionnaire de la blocklist locale BlokQR — architecture 3 couches.
 *
 * Couches (par ordre de priorité au démarrage) :
 *   1. BUNDLED  : embarquée dans l'APK (assets/blocklist-bundle.json, ~150 dom.).
 *                 Toujours disponible, même au tout premier lancement hors-ligne.
 *   2. CACHED   : téléchargée précédemment depuis /v1/local-blocklist et stockée
 *                 dans `filesDir/blocklist-cached.json` (~500 domaines).
 *                 Utilisée si signature valide ET non expirée ET plus récente
 *                 que la bundled.
 *   3. REFRESH  : déclenchée par BlocklistRefreshWorker (WorkManager, 24h).
 *                 Le nouveau fichier validé remplace le Cached actuel.
 *
 * Garde-fous :
 *   - Bundled est TOUJOURS chargée au démarrage (filet de sécurité absolu).
 *   - Cached n'est utilisée qu'après vérification de signature hybride.
 *   - Cached expirée -> ignorée (retour au Bundled).
 *   - Signature invalide d'un Cached -> fichier supprimé silencieusement.
 *   - Threadsafe : `@Volatile` + Mutex pour les opérations critiques.
 *
 * Usage :
 *   1. Appeler `BlocklistManager.initialize(applicationContext, trustedKeysProvider)`
 *      au démarrage (BlokQrApp.onCreate, en thread d'arrière-plan).
 *   2. LocalAnalyzer appelle `contains(domain)` à chaque scan local.
 *   3. BlocklistRefreshWorker appelle `installFreshBlocklist(bytes)` après
 *      téléchargement réussi.
 */
object BlocklistManager {

    private const val TAG = "BlocklistManager"
    private const val CACHE_FILE_NAME = "blocklist-cached.json"
    private const val BUNDLE_ASSET_PATH = "blocklist-bundle.json"

    /** État courant de la blocklist active. `@Volatile` pour lecture lock-free
     *  par LocalAnalyzer (chemin chaud du scan). */
    @Volatile private var active: SignedBlocklist? = null

    /** Provider injecté du manifeste de clés courantes (résolu paresseusement
     *  pour éviter les dépendances circulaires avec BlokQrApi). */
    @Volatile private var trustedKeysProvider: (() -> KeyManifest.TrustedKeys?)? = null

    private val initMutex = Mutex()
    private var initialized = false

    // ----------------------------------------------------------------------- //
    //  API publique
    // ----------------------------------------------------------------------- //

    /**
     * Initialisation paresseuse. Appel idempotent : multiples appels ne posent
     * pas de problème (verrou interne).
     *
     * Appelle ensuite, en arrière-plan :
     *   1. Charge la bundled depuis assets.
     *   2. Tente de charger le cache disk si plus récent.
     *   3. Définit `active` au meilleur des deux.
     *
     * @param context Application context (pour accès assets + filesDir).
     * @param trustedKeysProvider Lambda qui renvoie les TrustedKeys validées
     *        via le manifeste SLH-DSA. Peut renvoyer null si manifeste pas
     *        encore disponible : la bundled est alors quand même chargée
     *        (sans vérification clé/manifeste -- signature interne validée).
     */
    suspend fun initialize(
        context: Context,
        trustedKeysProvider: (() -> KeyManifest.TrustedKeys?)? = null,
    ) {
        initMutex.withLock {
            if (initialized) return
            this.trustedKeysProvider = trustedKeysProvider

            // 1. Bundled : toujours chargée (filet de sécurité).
            val bundled = loadBundled(context)

            // 2. Cached : tentative, peut échouer (fichier absent, expiré,
            //    signature invalide).
            val cached = loadCached(context)

            // 3. Choisit la meilleure source : Cached gagne si plus récente.
            val best = chooseBest(bundled, cached)
            active = best
            initialized = true

            if (best != null) {
                Log.i(TAG, "Blocklist active: ${best.domainCount} domaines, " +
                    "version=${best.version}, expire=${best.expiresAt}")
            } else {
                Log.w(TAG, "Aucune blocklist disponible (ni bundled ni cached).")
            }
        }
    }

    /**
     * Variante synchrone bloquante pour les contextes non-coroutine.
     * Utile depuis BlokQrApp.onCreate() pour s'assurer que `contains()` est
     * exploitable avant le premier scan utilisateur.
     *
     * Exécutée en thread d'arrière-plan ; ne bloque pas le main thread si
     * appelée depuis un coroutine context différent.
     */
    fun initializeBlocking(
        context: Context,
        trustedKeysProvider: (() -> KeyManifest.TrustedKeys?)? = null,
    ) = runBlocking(Dispatchers.IO) {
        initialize(context.applicationContext, trustedKeysProvider)
    }

    /**
     * Vérifie si un domaine est dans la blocklist active.
     * Lookup O(1) sur Set. Appelé sur le chemin chaud des scans.
     *
     * Pour cohérence avec la normalisation côté builder :
     *   - Le domaine reçu peut être avec ou sans 'www.' : on teste les deux.
     *   - Les confusables sont normalisés AVANT lookup (gооgle.com → google.com).
     */
    fun contains(domain: String): Boolean {
        val a = active ?: return false
        val normalized = LocalThreatData.normalizeConfusables(domain.lowercase())
        if (a.domains.contains(normalized)) return true
        // Fallback : si le domaine commence par "www.", essayer sans.
        if (normalized.startsWith("www.") && normalized.length > 4) {
            return a.domains.contains(normalized.substring(4))
        }
        return false
    }

    /** Informations sur la blocklist active (pour debug About / diagnostics). */
    fun currentInfo(): BlocklistInfo? {
        val a = active ?: return null
        return BlocklistInfo(
            version = a.version,
            generatedAt = a.generatedAt,
            expiresAt = a.expiresAt,
            keyId = a.keyId,
            sources = a.sources,
            domainCount = a.domainCount,
            source = if (a.version > 0) "signed" else "unknown",
        )
    }

    /**
     * Appelé par BlocklistRefreshWorker après téléchargement réussi.
     * Vérifie la signature, et si OK, remplace l'active + écrit le cache disk.
     *
     * @return true si la nouvelle blocklist est acceptée, false sinon.
     */
    suspend fun installFreshBlocklist(context: Context, freshJson: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            val trusted = trustedKeysProvider?.invoke()
            val result = BlocklistSignatureVerifier.verify(freshJson, trusted)
            if (!result.isValid) {
                Log.w(TAG, "Refresh rejete : ${result.reason}")
                return@withContext false
            }
            val newBlocklist = result.blocklist!!

            // Ne pas régresser : si on a déjà une version plus récente, on garde.
            val current = active
            if (current != null && current.version >= newBlocklist.version) {
                Log.i(TAG, "Refresh ignore : version actuelle (${current.version}) " +
                    ">= version nouvelle (${newBlocklist.version}).")
                return@withContext false
            }

            // Écriture atomique du cache disk (.tmp + rename).
            try {
                val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
                val tmpFile = File(context.filesDir, "$CACHE_FILE_NAME.tmp")
                tmpFile.writeBytes(freshJson)
                if (!tmpFile.renameTo(cacheFile)) {
                    cacheFile.delete()
                    if (!tmpFile.renameTo(cacheFile)) {
                        Log.w(TAG, "Renommage cache disk echoue.")
                        tmpFile.delete()
                        // On garde quand même en mémoire, juste le disque est manqué.
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Ecriture cache disk echouee", e)
                // Ce n'est pas bloquant : la nouvelle blocklist est utilisée
                // en mémoire le temps de cette session.
            }

            // Activation atomique : remplace `active` par la nouvelle.
            active = newBlocklist
            Log.i(TAG, "Refresh OK : ${newBlocklist.domainCount} domaines, " +
                "version=${newBlocklist.version}")
            true
        }
    }

    // ----------------------------------------------------------------------- //
    //  Internals
    // ----------------------------------------------------------------------- //

    /**
     * Charge la blocklist embarquée dans l'APK depuis assets/.
     * Doit toujours réussir si l'APK est intègre. Sinon, log erreur et
     * retourne null (l'app continue sans protection blocklist).
     */
    private suspend fun loadBundled(context: Context): SignedBlocklist? = withContext(Dispatchers.IO) {
        try {
            val bytes = context.assets.open(BUNDLE_ASSET_PATH).use { it.readBytes() }
            val trusted = trustedKeysProvider?.invoke()
            val result = BlocklistSignatureVerifier.verify(bytes, trusted)
            if (!result.isValid) {
                Log.w(TAG, "Bundled blocklist rejetee : ${result.reason}")
                return@withContext null
            }
            Log.i(TAG, "Bundled OK : ${result.blocklist!!.domainCount} domaines")
            result.blocklist
        } catch (e: IOException) {
            Log.w(TAG, "Bundled introuvable ou illisible : ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Bundled : erreur inattendue", e)
            null
        }
    }

    /**
     * Charge le cache disk si présent et valide.
     * Échec silencieux possible : fichier absent (premier lancement), signature
     * invalide (corruption / clé changée), expirée (cron pas tourné depuis 7j).
     */
    private suspend fun loadCached(context: Context): SignedBlocklist? = withContext(Dispatchers.IO) {
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (!cacheFile.exists()) return@withContext null
        try {
            val bytes = cacheFile.readBytes()
            val trusted = trustedKeysProvider?.invoke()
            val result = BlocklistSignatureVerifier.verify(bytes, trusted)
            if (!result.isValid) {
                Log.w(TAG, "Cached blocklist rejetee : ${result.reason}. Suppression.")
                cacheFile.delete()
                return@withContext null
            }
            Log.i(TAG, "Cached OK : ${result.blocklist!!.domainCount} domaines, " +
                "version=${result.blocklist.version}")
            result.blocklist
        } catch (e: Exception) {
            Log.w(TAG, "Cached : erreur de lecture", e)
            null
        }
    }

    /** Choisit la meilleure entre bundled et cached : la plus récente gagne. */
    private fun chooseBest(
        bundled: SignedBlocklist?,
        cached: SignedBlocklist?,
    ): SignedBlocklist? {
        if (bundled == null) return cached
        if (cached == null) return bundled
        return if (cached.version > bundled.version) cached else bundled
    }
}

/**
 * Métadonnées de la blocklist active. Exposées pour debug About.
 */
data class BlocklistInfo(
    val version: Long,
    val generatedAt: String,
    val expiresAt: String,
    val keyId: String,
    val sources: List<String>,
    val domainCount: Int,
    val source: String,
)
