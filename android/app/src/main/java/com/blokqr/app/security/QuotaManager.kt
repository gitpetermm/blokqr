package com.blokqr.app.security

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * État du quota quotidien, côté client.
 *
 * Source de vérité : le SERVEUR (compteur Redis). Le client ne fait que
 * REFLÉTER ce que le serveur lui dit, via :
 *   - le corps JSON d'une réponse 429 (quota_exceeded), parsé ici ;
 *   - (optionnel, non bloquant) un GET /v1/quota proactif.
 *
 * Ce manager ne décide JAMAIS seul qu'un quota est épuisé : il se contente de
 * mémoriser la dernière information reçue du serveur pour piloter l'UI
 * (bandeau, écran « quota atteint »). L'arbitre reste le backend.
 */
object QuotaManager {

    private val json = Json { ignoreUnknownKeys = true }

    /** Dernier état connu du quota (null si jamais renseigné). */
    @Volatile
    var last: QuotaSnapshot? = null
        private set

    /**
     * Parse le corps JSON d'une réponse 429 quota_exceeded.
     *
     * Le message d'AnalysisException a la forme "quota_exceeded:<json>" (voir
     * BlokQrApi.executeAnalyze). On extrait la partie JSON et on la désérialise.
     * Retourne null si le parsing échoue (l'appelant retombe alors sur un état
     * générique « quota atteint » sans détails).
     */
    fun parseQuotaExceeded(exceptionMessage: String?): QuotaSnapshot? {
        if (exceptionMessage == null) return null
        val marker = "quota_exceeded:"
        val idx = exceptionMessage.indexOf(marker)
        if (idx < 0) return null
        val jsonPart = exceptionMessage.substring(idx + marker.length).trim()
        if (jsonPart.isEmpty()) return null
        return try {
            val dto = json.decodeFromString(QuotaExceededDto.serializer(), jsonPart)
            QuotaSnapshot(
                isPro = dto.isPro,
                limit = dto.limit,
                used = dto.used,
                remaining = dto.remaining,
                resetAtIso = dto.resetAt,
                resetInSeconds = dto.resetInSeconds,
                recommendationPrimary = dto.recommendation?.primary ?: "upgrade_pro",
            ).also { last = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Met à jour l'état depuis un GET /v1/quota (lecture proactive). */
    fun updateFromQuotaStatus(jsonBody: String): QuotaSnapshot? {
        return try {
            val dto = json.decodeFromString(QuotaStatusDto.serializer(), jsonBody)
            QuotaSnapshot(
                isPro = dto.isPro,
                limit = dto.limit,
                used = dto.used,
                remaining = dto.remaining,
                resetAtIso = dto.resetAt,
                resetInSeconds = dto.resetInSeconds,
                recommendationPrimary = if (dto.isPro) "contact_support" else "upgrade_pro",
            ).also { last = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Réinitialise l'état connu (ex : après un reset de quota côté serveur). */
    fun clear() {
        last = null
    }

    // --- DTOs de désérialisation ------------------------------------------- //

    @Serializable
    private data class QuotaExceededDto(
        val detail: String = "quota_exceeded",
        @SerialName("is_pro") val isPro: Boolean = false,
        val limit: Int = 0,
        val used: Int = 0,
        val remaining: Int = 0,
        @SerialName("reset_at") val resetAt: String = "",
        @SerialName("reset_in_seconds") val resetInSeconds: Int = 0,
        val recommendation: RecommendationDto? = null,
        @SerialName("signed_attestation") val signedAttestation: String = "",
    )

    @Serializable
    private data class RecommendationDto(
        val primary: String = "upgrade_pro",
        val secondary: String = "retry_after",
        @SerialName("retry_after_seconds") val retryAfterSeconds: Int = 0,
    )

    @Serializable
    private data class QuotaStatusDto(
        @SerialName("is_pro") val isPro: Boolean = false,
        val limit: Int = 0,
        val used: Int = 0,
        val remaining: Int = 0,
        @SerialName("reset_at") val resetAt: String = "",
        @SerialName("reset_in_seconds") val resetInSeconds: Int = 0,
    )
}

/**
 * Instantané immuable de l'état du quota, pour l'UI.
 *
 * @param resetInSeconds secondes restantes jusqu'au reset (minuit UTC côté
 *        serveur). Le client convertit en heure locale pour l'affichage.
 */
data class QuotaSnapshot(
    val isPro: Boolean,
    val limit: Int,
    val used: Int,
    val remaining: Int,
    val resetAtIso: String,
    val resetInSeconds: Int,
    val recommendationPrimary: String,
) {
    /** Vrai si le quota est totalement consommé. */
    val isExhausted: Boolean get() = remaining <= 0

    /** Vrai si on approche de la limite (pour un bandeau d'avertissement). */
    val isNearLimit: Boolean get() = !isExhausted && remaining <= NEAR_LIMIT_THRESHOLD

    companion object {
        /** En dessous de ce nombre de scans restants, on affiche un bandeau. */
        const val NEAR_LIMIT_THRESHOLD = 2
    }
}