package com.blokqr.app.net

import com.blokqr.app.Config
import com.blokqr.app.crypto.KeyManifest
import com.blokqr.app.crypto.UrlNormalizer
import com.blokqr.app.crypto.VerdictVerifier
import com.blokqr.app.model.AnalyzeRequest
import com.blokqr.app.model.KeyManifestDto
import com.blokqr.app.model.SignedVerdictDto
import com.blokqr.app.model.VerifiedResult
import com.blokqr.app.model.Verdict
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class AnalysisException(message: String) : Exception(message)

/**
 * Client du service BlokQR.
 *
 * - TLS + épinglage de certificat (CertificatePinner) ; en production, viser un
 *   échange de clés hybride X25519+ML-KEM côté terminaison TLS.
 * - Récupération et vérification du MANIFESTE de clés (signé SLH-DSA) : seule la
 *   racine SLH-DSA est épinglée ; les clés de verdict tournent librement.
 * - Vérification OBLIGATOIRE et HYBRIDE (Ed25519 + ML-DSA-65) du verdict, avec
 *   contrôle de fraîcheur et liaison du rapport.
 * - Palier 1 de réputation k-anonyme interrogé séparément (préfixes seulement),
 *   idéalement via relais OHTTP, et chiffrable via l'enveloppe ML-KEM.
 */
class BlokQrApi {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMedia = "application/json".toMediaType()
    private val secureRandom = SecureRandom()

    // Cache du manifeste vérifié (clés courantes + échéance).
    @Volatile private var trusted: KeyManifest.TrustedKeys? = null
    @Volatile private var trustedUntil: Long = 0

    private val client: OkHttpClient by lazy {
        val host = Config.API_BASE_URL.toHttpUrl().host
        val builder = OkHttpClient.Builder()
            .callTimeout(Config.NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
        if (!Config.CERT_PIN_SHA256.endsWith("AAA=")) {
            builder.certificatePinner(
                CertificatePinner.Builder().add(host, Config.CERT_PIN_SHA256).build()
            )
        }
        builder.build()
    }

    private fun newNonce(): String {
        val b = ByteArray(16)
        secureRandom.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** Récupère et vérifie le manifeste de clés (avec cache). */
    private fun trustedKeys(): KeyManifest.TrustedKeys {
        val now = System.currentTimeMillis() / 1000
        trusted?.let { if (now < trustedUntil) return it }

        val httpReq = Request.Builder().url("${Config.API_BASE_URL}/manifest").get().build()
        client.newCall(httpReq).execute().use { resp ->
            if (!resp.isSuccessful) throw AnalysisException("Manifeste indisponible (HTTP ${resp.code}).")
            val text = resp.body?.string() ?: throw AnalysisException("Manifeste vide.")
            val dto = json.decodeFromString(KeyManifestDto.serializer(), text)
            val res = KeyManifest.verify(dto, Config.PINNED_SLHDSA_ROOT_PUBKEY_B64)
                ?: throw AnalysisException("Manifeste invalide.")
            val keys = res.keys ?: throw AnalysisException("Manifeste rejeté : ${res.reason}")
            trusted = keys
            // Re-vérification du manifeste au moins toutes les 6 h.
            trustedUntil = now + 6 * 3600
            return keys
        }
    }

    /**
     * Analyse approfondie (palier 2). L'URL complète est transmise (nécessaire
     * pour suivre les redirections et rendre la page) ; via relais OHTTP, l'IP
     * reste masquée. Pour une capability-URL, n'appeler qu'avec consentement.
     */
    fun analyze(
        rawPayload: String,
        symbology: String?,
        priorDestinationHash: String?,
        coarseGeohash: String?,
        consentDeepAnalysis: Boolean = false,
        sourceHash: String? = null,
    ): VerifiedResult {
        val keys = if (Config.REQUIRE_SIGNED_VERDICT) trustedKeys() else null
        val nonce = newNonce()
        val req = AnalyzeRequest(
            rawPayload = rawPayload,
            symbology = symbology,
            clientNonce = nonce,
            priorDestinationHash = priorDestinationHash,
            coarseGeohash = coarseGeohash,
            wantScreenshot = true,
            consentDeepAnalysis = consentDeepAnalysis,
            sourceHash = sourceHash,
        )
        val body = json.encodeToString(AnalyzeRequest.serializer(), req).toRequestBody(jsonMedia)
        val httpReq = Request.Builder().url("${Config.API_BASE_URL}/v1/analyze").post(body).build()

        client.newCall(httpReq).execute().use { resp ->
            if (!resp.isSuccessful) throw AnalysisException("Service indisponible (HTTP ${resp.code}).")
            val payload = resp.body?.string() ?: throw AnalysisException("Réponse vide du service.")
            val dto = json.decodeFromString(SignedVerdictDto.serializer(), payload)

            var verified = false
            if (Config.REQUIRE_SIGNED_VERDICT && keys != null) {
                // La clé du verdict doit correspondre à celle du manifeste.
                if (dto.keyId != keys.keyId) {
                    throw AnalysisException("Identifiant de clé non conforme au manifeste.")
                }
                val outcome = VerdictVerifier.verify(
                    dto, expectedNonce = nonce, trusted = keys,
                    requirePq = Config.REQUIRE_PQ_VERIFICATION,
                )
                if (!outcome.verified) throw AnalysisException("Verdict non authentifié : ${outcome.reason}")
                verified = true
            }

            return VerifiedResult(
                verdict = Verdict.fromWire(dto.verdict),
                score = dto.score,
                report = dto.report,
                signatureVerified = verified,
                rawType = dto.report.payloadType,
            )
        }
    }

    /**
     * Palier 1 — réputation k-anonyme : n'envoie que des préfixes de hash.
     * La correspondance finale est effectuée EN LOCAL.
     */
    fun reputationMalicious(url: String): Boolean {
        val fp = UrlNormalizer.fingerprint(url)
        val payload = buildString {
            append("{\"prefixes\":[")
            append(fp.expressionPrefixes.joinToString(",") { "\"$it\"" })
            append("]}")
        }.toRequestBody(jsonMedia)

        val httpReq = Request.Builder().url("${Config.API_BASE_URL}/v1/reputation").post(payload).build()
        client.newCall(httpReq).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val text = resp.body?.string() ?: return false
            val localHashes = UrlNormalizer.expressionsOf(url)
                .map { UrlNormalizer.fullExpressionHash(it) }.toSet()
            return localHashes.any { text.contains(it, ignoreCase = true) }
        }
    }
}
