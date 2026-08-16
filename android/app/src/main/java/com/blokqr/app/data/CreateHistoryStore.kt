package com.blokqr.app.data
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
 * Un code CRÉÉ par l'utilisateur, mémorisé dans l'historique des créations.
 * `label` est un libellé lisible qui ne contient JAMAIS de secret (pour le Wi-Fi,
 * on stocke le SSID, pas le mot de passe) ; le `payload` complet (qui, lui, peut
 * contenir le mot de passe) sert à régénérer le code et n'est lu qu'au détail.
 */
@Serializable
data class CreatedCode(
    val label: String,
    val payload: String,
    val format: String,   // CodeFormat.name
    val timestamp: Long,
)
/**
 * Historique LOCAL des codes CRÉÉS — CHIFFRÉ AU REPOS.
 *
 * Un code Wi-Fi contient le mot de passe en clair dans son payload : le
 * chiffrement est donc indispensable. Mécanique identique à ScanLogStore :
 * clé AES-256-GCM non exportable de l'Android Keystore (adossée au matériel si
 * disponible), IV aléatoire par écriture, blob [version][lenIV][IV][chiffré+tag].
 * Écritures sérialisées par Mutex, écriture atomique (fichier temporaire +
 * renommage), lecture tolérante (illisible -> liste vide), plafond MAX_ENTRIES.
 * Toutes les opérations disque sont `suspend` (Dispatchers.IO).
 *
 * Contrôle utilisateur : `isEnabled()`/`setEnabled()` (défaut ON) et `clear()`.
 */
class CreateHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    @Volatile
    private var cachedKey: SecretKey? = null
    /** Enregistrement actif ? (défaut oui). */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
    /** Liste, du plus récent au plus ancien. */
    suspend fun list(): List<CreatedCode> = withContext(Dispatchers.IO) {
        mutex.withLock { readUnlocked() }
    }
    /**
     * Ajoute (ou remonte) un code en tête. Dédoublonnage : un code identique
     * (même payload + même format) est déplacé en tête plutôt qu'empilé.
     */
    suspend fun save(entry: CreatedCode) {
        if (!isEnabled()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readUnlocked().toMutableList()
                current.removeAll { it.payload == entry.payload && it.format == entry.format }
                current.add(0, entry)
                writeUnlocked(current.take(MAX_ENTRIES))
            }
        }
    }
    /** Supprime une entrée précise (identifiée par payload+format+horodatage). */
    suspend fun delete(entry: CreatedCode) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readUnlocked().toMutableList()
                current.removeAll {
                    it.payload == entry.payload &&
                        it.format == entry.format &&
                        it.timestamp == entry.timestamp
                }
                writeUnlocked(current)
            }
        }
    }
    /** Efface tout l'historique des créations. */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock { runCatching { if (file.exists()) file.delete() } }
        }
    }
    // ---------------------------------------------------------------------
    private fun readUnlocked(): List<CreatedCode> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val plain = decrypt(file.readBytes())
            json.decodeFromString<List<CreatedCode>>(plain.decodeToString())
        }.getOrDefault(emptyList())
    }
    private fun writeUnlocked(entries: List<CreatedCode>) {
        runCatching {
            val payload = json.encodeToString(entries).encodeToByteArray()
            val blob = encrypt(payload)
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeBytes(blob)
            if (!tmp.renameTo(file)) {
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
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        val out = ByteArray(2 + iv.size + ct.size)
        out[0] = VERSION
        out[1] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 2, iv.size)
        System.arraycopy(ct, 0, out, 2 + iv.size, ct.size)
        return out
    }
    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= 2 && blob[0] == VERSION) { "format create_history inconnu" }
        val ivLen = blob[1].toInt() and 0xFF
        require(blob.size >= 2 + ivLen) { "blob create_history tronqué" }
        val iv = blob.copyOfRange(2, 2 + ivLen)
        val ct = blob.copyOfRange(2 + ivLen, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
    companion object {
        private const val FILE_NAME = "create_history.enc"
        private const val PREFS_NAME = "create_history_prefs"
        private const val KEY_ENABLED = "enabled"
        /** Plafond glissant : au plus les 100 créations les plus récentes. */
        const val MAX_ENTRIES = 100
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "blokqr_create_history_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val VERSION: Byte = 1
    }
}
