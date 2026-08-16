package com.blokqr.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Cache LOCAL de l'entitlement Pro signé (émis par /v1/billing/verify).
 *
 * On stocke le jeton signé, une échéance « pro jusqu'à » (epoch) et, le cas
 * échéant, une échéance de fin d'essai gratuit (epoch). Ces valeurs ne sont
 * écrites qu'après une vérification cryptographique réussie. Elles ne servent
 * qu'à la continuité d'affichage hors-ligne (badge inclus) : l'accès réel à
 * l'analyse profonde reste imposé côté serveur (signature de l'entitlement
 * vérifiée sur /v1/analyze/deep). Aucune identité conservée (modèle bearer,
 * conception privacy-first).
 */
private val Context.entitlementDataStore by preferencesDataStore(name = "entitlement")

class EntitlementStore(private val context: Context) {

    private val tokenKey = stringPreferencesKey("pro_entitlement_token")
    private val proUntilKey = longPreferencesKey("pro_until_epoch")
    private val trialUntilKey = longPreferencesKey("trial_until_epoch")

    /** Entitlement en cache : jeton signé + échéance Pro + fin d'essai (epoch, s). */
    data class Cached(val token: String, val proUntil: Long, val trialUntil: Long = 0L)

    suspend fun load(): Cached? {
        val prefs = context.entitlementDataStore.data.first()
        val token = prefs[tokenKey] ?: return null
        return Cached(
            token = token,
            proUntil = prefs[proUntilKey] ?: 0L,
            trialUntil = prefs[trialUntilKey] ?: 0L,
        )
    }

    suspend fun save(token: String, proUntilEpoch: Long, trialUntilEpoch: Long = 0L) {
        context.entitlementDataStore.edit {
            it[tokenKey] = token
            it[proUntilKey] = proUntilEpoch
            it[trialUntilKey] = trialUntilEpoch
        }
    }

    suspend fun clear() {
        context.entitlementDataStore.edit {
            it.remove(tokenKey)
            it.remove(proUntilKey)
            it.remove(trialUntilKey)
        }
    }
}