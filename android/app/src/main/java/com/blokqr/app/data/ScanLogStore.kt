package com.blokqr.app.data
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
/**
 * Stockage LOCAL de l'historique des scans utilisateur — 100 % sur l'appareil,
 * et désormais CHIFFRÉ AU REPOS.
 *
 * Distinct de ScanHistoryStore (mémoire des empreintes de destination, pour
 * détecter destination_changed) : ici on conserve la liste des scans visible
 * par l'utilisateur.
 *
 * Chiffrement au repos :
 *   - clé AES-256-GCM générée et conservée dans l'ANDROID KEYSTORE (non
 *     exportable, adossée au matériel si l'appareil le permet) ;
 *   - chaque écriture du fichier `scan_log.enc` est chiffrée (AES/GCM/NoPadding)
 *     avec un IV aléatoire fourni par le système ;
 *   - format du blob : [version][longueur IV][IV][texte chiffré + tag GCM].
 *   Conséquence : même sur un appareil rooté, le contenu du fichier (URLs
 *   scannées, verdicts) reste illisible sans la clé du Keystore.
 *
 * Migration transparente : un éventuel ancien fichier en clair `scan_log.json`
 * est lu une dernière fois, ré-écrit chiffré, puis supprimé.
 *
 * Confidentialité / contrôle utilisateur :
 *   - `isEnabled()` / `setEnabled()` : interrupteur d'activation (par défaut ON).
 *     Désactivé -> plus aucune écriture (l'existant reste jusqu'à un effacement).
 *   - `remove()` : efface UNE entrée (par identité timestamp+valeur).
 *   - `clear()` : efface tout l'historique (chiffré et éventuel clair résiduel).
 *
 * Robustesse : écritures sérialisées par un Mutex, écriture atomique (fichier
 * temporaire + renommage — l'IV est embarqué dans le blob, le renommage ne
 * casse donc pas le déchiffrement), lecture tolérante (illisible -> liste vide),
 * plafond glissant MAX_ENTRIES. Toutes les opérations disque sont `suspend`
 * (Dispatchers.IO).
 *
 * API publique : list/add/remove/clear + isEnabled/setEnabled. L'ajout de
 * `remove` est rétro-compatible (aucun appelant existant n'est impacté).
 */
class ScanLogStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)             // chiffré
    private val legacyFile = File(appContext.filesDir, LEGACY_FILE_NAME) // ancien clair
    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    // Cache mémoire de la clé Keystore (récupérée/générée à la première utilisation).
    @Volatile
    private var cachedKey: SecretKey? = null
    /** Historique actif ? (par défaut oui). Lecture peu coûteuse, synchrone. */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)
    /** Active/désactive l'enregistrement. Ne supprime pas l'existant. */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
    /** Liste de l'historique, du plus récent au plus ancien. */
    suspend fun list(): List<ScanLogEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { readUnlocked() }
    }
    /**
     * Ajoute une entrée en tête (sans rien faire si l'historique est désactivé).
     * Si la même valeur est déjà en tête, on remplace (rafraîchit l'horodatage)
     * plutôt que d'empiler un doublon immédiat.
     */
    suspend fun add(entry: ScanLogEntry) {
        if (!isEnabled()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readUnlocked().toMutableList()
                if (current.firstOrNull()?.value == entry.value) {
                    current[0] = entry
                } else {
                    current.add(0, entry)
                }
                writeUnlocked(current.take(MAX_ENTRIES))
            }
        }
    }
    /**
     * Supprime UNE entrée identifiée par sa paire (timestamp, valeur) — stable et
     * unique en pratique (deux scans distincts ne partagent pas le même
     * horodatage à la milliseconde pour la même valeur). Réécrit le fichier
     * chiffré. Sans effet si l'entrée n'existe pas.
     */
    suspend fun remove(timestamp: Long, value: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readUnlocked()
                val filtered = current.filterNot {
                    it.timestamp == timestamp && it.value == value
                }
                if (filtered.size != current.size) {
                    writeUnlocked(filtered)
                }
            }
        }
    }
    /** Efface tout l'historique (fichier chiffré et éventuel clair résiduel). */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { if (file.exists()) file.delete() }
                runCatching { if (legacyFile.exists()) legacyFile.delete() }
            }
        }
    }
    // ---------------------------------------------------------------------
    // Lecture
    // ---------------------------------------------------------------------
    private fun readUnlocked(): List<ScanLogEntry> {
        // 1) Format chiffré courant.
        if (file.exists()) {
            return runCatching {
                val plain = decrypt(file.readBytes())
                json.decodeFromString<List<ScanLogEntry>>(plain.decodeToString())
            }.getOrDefault(emptyList())
        }
        // 2) Migration : ancien fichier en clair -> on le rechiffre, puis on le supprime.
        if (legacyFile.exists()) {
            val migrated = runCatching {
                json.decodeFromString<List<ScanLogEntry>>(legacyFile.readText())
            }.getOrDefault(emptyList())
            runCatching { writeUnlocked(migrated) }   // écrit scan_log.enc
            runCatching { legacyFile.delete() }        // efface le clair
            return migrated
        }
        return emptyList()
    }
    // ---------------------------------------------------------------------
    // Écriture (atomique + chiffrée)
    // ---------------------------------------------------------------------
    private fun writeUnlocked(entries: List<ScanLogEntry>) {
        runCatching {
            val payload = json.encodeToString(entries).encodeToByteArray()
            val blob = encrypt(payload)
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeBytes(blob)
            if (!tmp.renameTo(file)) {
                // Repli : écriture directe si le renommage atomique échoue.
                file.writeBytes(blob)
                tmp.delete()
            }
        }
    }
    // ---------------------------------------------------------------------
    // Cryptographie : AES-256-GCM, clé non exportable de l'Android Keystore
    // ---------------------------------------------------------------------
    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val key = existing ?: run {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            kg.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            kg.generateKey()
        }
        cachedKey = key
        return key
    }
    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv                       // IV aléatoire généré par le système
        val ct = cipher.doFinal(plain)           // texte chiffré + tag GCM (16 o)
        val out = ByteArray(2 + iv.size + ct.size)
        out[0] = VERSION
        out[1] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 2, iv.size)
        System.arraycopy(ct, 0, out, 2 + iv.size, ct.size)
        return out
    }
    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= 2 && blob[0] == VERSION) { "format scan_log inconnu" }
        val ivLen = blob[1].toInt() and 0xFF
        require(blob.size >= 2 + ivLen) { "blob scan_log tronqué" }
        val iv = blob.copyOfRange(2, 2 + ivLen)
        val ct = blob.copyOfRange(2 + ivLen, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
    companion object {
        private const val FILE_NAME = "scan_log.enc"
        private const val LEGACY_FILE_NAME = "scan_log.json"
        private const val PREFS_NAME = "scan_log_prefs"
        private const val KEY_ENABLED = "enabled"
        /** Plafond glissant : au plus les 200 scans les plus récents. */
        const val MAX_ENTRIES = 200
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "blokqr_scan_log_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val VERSION: Byte = 1
    }
}