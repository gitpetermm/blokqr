package com.blokqr.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modèles de transport (JSON) reflétant les schémas du backend.
 * Les champs inconnus sont ignorés (Json { ignoreUnknownKeys = true }).
 */

@Serializable
data class AnalyzeRequest(
    @SerialName("raw_payload") val rawPayload: String,
    val symbology: String? = null,
    @SerialName("client_nonce") val clientNonce: String,
    @SerialName("prior_destination_hash") val priorDestinationHash: String? = null,
    @SerialName("coarse_geohash") val coarseGeohash: String? = null,
    @SerialName("want_screenshot") val wantScreenshot: Boolean = true,
    @SerialName("consent_deep_analysis") val consentDeepAnalysis: Boolean = false,
    @SerialName("source_hash") val sourceHash: String? = null,
)

@Serializable
data class SignalDto(
    val code: String,
    val title: String,
    val detail: String = "",
    val severity: String = "info",
    val weight: Int = 0,
    val source: String = "",
)

@Serializable
data class RedirectHopDto(
    val index: Int,
    val url: String,
    @SerialName("status_code") val statusCode: Int? = null,
    val method: String = "GET",
    val kind: String = "http",
    @SerialName("resolved_ip") val resolvedIp: String? = null,
    val server: String? = null,
)

@Serializable
data class ThreatIntelDto(
    val provider: String,
    val malicious: Boolean = false,
    val available: Boolean = true,
    val categories: List<String> = emptyList(),
    val detail: String = "",
)

@Serializable
data class AnalysisReportDto(
    @SerialName("payload_type") val payloadType: String,
    @SerialName("displayed_value") val displayedValue: String,
    @SerialName("original_target") val originalTarget: String? = null,
    @SerialName("final_target") val finalTarget: String? = null,
    @SerialName("redirect_chain") val redirectChain: List<RedirectHopDto> = emptyList(),
    @SerialName("threat_intel") val threatIntel: List<ThreatIntelDto> = emptyList(),
    val signals: List<SignalDto> = emptyList(),
    @SerialName("cloaking_detected") val cloakingDetected: Boolean = false,
    @SerialName("login_page_impersonation") val loginImpersonation: String? = null,
    @SerialName("qrljacking_suspected") val qrljackingSuspected: Boolean = false,
    @SerialName("destination_changed") val destinationChanged: Boolean = false,
    val reasons: List<String> = emptyList(),
    @SerialName("screenshot_b64") val screenshotB64: String? = null,
    @SerialName("current_destination_hash") val currentDestinationHash: String? = null,
    @SerialName("domain_registrable") val domainRegistrable: String? = null,
    @SerialName("capability_url") val capabilityUrl: Boolean = false,
    @SerialName("privacy_hold") val privacyHold: Boolean = false,
    @SerialName("gating_detected") val gatingDetected: Boolean = false,
    @SerialName("diverges_consensus") val divergesConsensus: Boolean = false,
)

@Serializable
data class SignedVerdictDto(
    val verdict: String,
    val score: Int,
    val report: AnalysisReportDto,
    @SerialName("client_nonce") val clientNonce: String,
    @SerialName("issued_at") val issuedAt: String,
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("report_sha256") val reportSha256: String = "",
    @SerialName("canonical_payload") val canonicalPayload: String,
    @SerialName("signature_ed25519_b64") val signatureEd25519B64: String = "",
    @SerialName("signature_mldsa65_b64") val signatureMldsa65B64: String = "",
    @SerialName("public_key_ed25519_b64") val publicKeyEd25519B64: String = "",
    @SerialName("key_id") val keyId: String = "",
)

/** Manifeste de clés signé SLH-DSA (racine de confiance, rotation). */
@Serializable
data class KeyManifestDto(
    val version: Int = 1,
    @SerialName("key_id") val keyId: String = "",
    @SerialName("ed25519_pub_b64") val ed25519PubB64: String = "",
    @SerialName("mldsa65_pub_b64") val mldsa65PubB64: String = "",
    @SerialName("mlkem768_pub_b64") val mlkem768PubB64: String = "",
    @SerialName("issued_at") val issuedAt: String = "",
    @SerialName("not_after") val notAfter: String = "",
    @SerialName("root_alg") val rootAlg: String = "",
    val canonical: String = "",
    @SerialName("sig_slhdsa_b64") val sigSlhdsaB64: String = "",
    @SerialName("root_pub_b64") val rootPubB64: String = "",
)

/** Clés publiques de la passerelle pour l'enveloppe hybride ML-KEM + X25519. */
@Serializable
data class GatewayKeyDto(
    @SerialName("mlkem768_pub") val mlkem768Pub: String = "",
    @SerialName("x25519_pub") val x25519Pub: String = "",
    val alg: String = "",
)

/** Résultat applicatif : verdict vérifié + rapport, prêt pour l'UI. */
data class VerifiedResult(
    val verdict: Verdict,
    val score: Int,
    val report: AnalysisReportDto,
    val signatureVerified: Boolean,
    val rawType: String,
) {
    val isNavigable: Boolean get() = rawType == "url"
}
