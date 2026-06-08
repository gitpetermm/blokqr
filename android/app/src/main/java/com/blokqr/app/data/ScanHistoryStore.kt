package com.blokqr.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.blokqr.app.crypto.UrlNormalizer
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/**
 * Mémoire LOCALE des destinations déjà observées, pour la détection contextuelle
 * temporelle (un QR qui change de destination dans le temps).
 *
 * On indexe par hash de la SOURCE scannée (jamais l'URL en clair) et on conserve
 * le dernier hash de destination renvoyé par le service. Tout reste sur l'appareil.
 */
private val Context.dataStore by preferencesDataStore(name = "scan_history")

class ScanHistoryStore(private val context: Context) {

    private fun sourceKey(rawPayload: String): String {
        val canon = runCatching { UrlNormalizer.canonicalize(rawPayload) }
            .getOrDefault(rawPayload)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canon.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Dernière destination connue pour cette source (ou null). */
    suspend fun priorDestinationHash(rawPayload: String): String? {
        val key = stringPreferencesKey("dest_" + sourceKey(rawPayload))
        return context.dataStore.data.first()[key]
    }

    /** Mémorise la destination renvoyée par le service après analyse. */
    suspend fun remember(rawPayload: String, destinationHash: String?) {
        if (destinationHash.isNullOrEmpty()) return
        val key = stringPreferencesKey("dest_" + sourceKey(rawPayload))
        context.dataStore.edit { it[key] = destinationHash }
    }
}
