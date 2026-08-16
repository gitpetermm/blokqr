package com.blokqr.app.net
import com.blokqr.app.Config
import com.blokqr.app.crypto.EntitlementVerifier
import com.blokqr.app.crypto.KeyManifest
import com.blokqr.app.crypto.PqEnvelope
import com.blokqr.app.crypto.UrlNormalizer
import com.blokqr.app.crypto.VerdictVerifier
import com.blokqr.app.model.AnalyzeRequest
import com.blokqr.app.model.AnalysisReportDto
import com.blokqr.app.model.BillingVerifyRequest
import com.blokqr.app.model.BillingVerifyResponse
import com.blokqr.app.model.GatewayKeyDto
import com.blokqr.app.model.KeyManifestDto
import com.blokqr.app.model.SignedVerdictDto
import com.blokqr.app.model.VerifiedResult
import com.blokqr.app.model.Verdict
import com.blokqr.app.security.HmacInterceptor
import com.blokqr.app.security.InstallTokenManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * Résultat d'analyse enrichi de la nature de l'aperçu approfondi (header
 * X-Deep-Free renvoyé par /v1/analyze/deep) : GRANTED = aperçu offert du jour,
 * PRO = utilisateur abonné, NONE = analyse standard (pas un deep).
 */
data class DeepOutcome(
    val result: VerifiedResult,
    val deepFree: DeepFreeStatus,
)
enum class DeepFreeStatus { NONE, GRANTED, PRO }
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
 * - Authentification HMAC par installation (X-Install-Id + X-Timestamp +
 *   X-Nonce + X-Hmac) injectée automatiquement par HmacInterceptor pour toutes
 *   les requêtes /v1/... (hors /v1/install qui est lui-même le provisionnement).
 *
 * @param installTokenManager source de l'install_id + secret HMAC pour
 *        l'authentification HMAC par installation. Injecté pour permettre les
 *        tests unitaires (mock) et garder un seul singleton applicatif.
 */
class BlokQrApi(
    private val installTokenManager: InstallTokenManager,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMedia = "application/json".toMediaType()
    private val secureRandom = SecureRandom()
    // Cache du manifeste vérifié (clés courantes + échéance).
    @Volatile private var trusted: KeyManifest.TrustedKeys? = null
    @Volatile private var trustedUntil: Long = 0
    // Cache de la clé publique de passerelle (enveloppe ML-KEM-768 + X25519).
    @Volatile private var pqGateway: GatewayKeyDto? = null
    @Volatile private var pqGatewayUntil: Long = 0
    // La clé passerelle est persistante côté serveur ; on la rafraîchit
    // périodiquement pour capter une éventuelle rotation.
    private val pqKeyTtlSeconds: Long = 6L * 3600
    private val client: OkHttpClient by lazy {
        val host = Config.API_BASE_URL.toHttpUrl().host
        val builder = OkHttpClient.Builder()
            .callTimeout(Config.NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            // HMAC injecté en PREMIER : il signe les octets après toute autre
            // transformation applicative (l'enveloppe PQ est appliquée plus haut,
            // au niveau bodyJson, donc le HMAC signe bien ce qui est envoyé).
            .addInterceptor(HmacInterceptor(installTokenManager))
        if (!Config.CERT_PIN_SHA256.endsWith("AAA=")) {
            builder.certificatePinner(
                CertificatePinner.Builder()
                    .add(host, *Config.CERT_PINS_SHA256.toTypedArray())
                    .build()
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
     * Cle publique de passerelle pour l'enveloppe (ML-KEM-768 + X25519), avec
     * cache + TTL, via GET /pq-pubkey. Ne leve JAMAIS : renvoie null si
     * l'enveloppe est desactivee ou indisponible -- l'appelant repart alors en
     * clair (fail-open), le verdict restant signe et verifie.
     */
    private fun gatewayKey(): GatewayKeyDto? {
        if (!Config.ENABLE_PQ_ENVELOPE) return null
        val now = System.currentTimeMillis() / 1000
        pqGateway?.let { if (now < pqGatewayUntil) return it }
        return try {
            val httpReq = Request.Builder().url("${Config.API_BASE_URL}/pq-pubkey").get().build()
            client.newCall(httpReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                val dto = json.decodeFromString(GatewayKeyDto.serializer(), text)
                if (dto.mlkem768Pub.isBlank() || dto.x25519Pub.isBlank()) return null
                pqGateway = dto
                pqGatewayUntil = now + pqKeyTtlSeconds
                dto
            }
        } catch (e: Exception) {
            null
        }
    }
    /**
     * Analyse RAPIDE (palier gratuit) : verdict signé, sans rendu Chromium.
     * L'URL complète est transmise (suivi des redirections) ; via relais OHTTP,
     * l'IP reste masquée. Pour une capability-URL, n'appeler qu'avec consentement.
     *
     * [entitlement] : jeton Pro signé en cache, joint quand il est disponible.
     * Il permet au serveur d'AUTO-RÉPARER le statut Pro côté quota dès ce scan
     * (badge Pro ⇔ quota Pro garantis synchrones, même après réinstallation).
     */
    fun analyze(
        rawPayload: String,
        symbology: String?,
        priorDestinationHash: String?,
        consentDeepAnalysis: Boolean = false,
        sourceHash: String? = null,
        entitlement: String? = null,
    ): VerifiedResult {
        val keys = if (Config.REQUIRE_SIGNED_VERDICT) trustedKeys() else null
        val nonce = newNonce()
        val req = AnalyzeRequest(
            rawPayload = rawPayload,
            symbology = symbology,
            clientNonce = nonce,
            priorDestinationHash = priorDestinationHash,
            wantScreenshot = true,
            consentDeepAnalysis = consentDeepAnalysis,
            sourceHash = sourceHash,
            entitlement = entitlement,
        )
        return runAnalyze("${Config.API_BASE_URL}/v1/analyze", req, nonce, keys)
    }
        /**
     * Analyse PROFONDE (rendu Chromium serveur). Deux usages :
     *  - Pro : [entitlement] = jeton signé -> analyse illimitée.
     *  - Gratuit : [entitlement] = null -> le serveur accorde l'aperçu offert
     *    du jour (1/jour) s'il reste disponible, sinon lève "pro_required" (402).
     *
     * Retourne un DeepOutcome portant le résultat ET la nature de l'aperçu
     * (header X-Deep-Free : granted | pro), pour que l'UI affiche le badge.
     * Lève AnalysisException("pro_required") sur 402 (offre épuisée / non Pro).
     */
    fun analyzeDeep(
        rawPayload: String,
        symbology: String?,
        priorDestinationHash: String?,
        entitlement: String?,
        sourceHash: String? = null,
    ): DeepOutcome {
        val keys = if (Config.REQUIRE_SIGNED_VERDICT) trustedKeys() else null
        val nonce = newNonce()
        val req = AnalyzeRequest(
            rawPayload = rawPayload,
            symbology = symbology,
            clientNonce = nonce,
            priorDestinationHash = priorDestinationHash,
            wantScreenshot = true,
            consentDeepAnalysis = true,
            sourceHash = sourceHash,
            entitlement = entitlement,
        )
        return runAnalyzeDeep("${Config.API_BASE_URL}/v1/analyze/deep", req, nonce, keys)
    }
    /**
     * Exécute l'appel (rapide ou profond) et vérifie le verdict signé.
     *
     * Confidentialité PQ (fail-open) : si l'enveloppe est disponible
     * (ENABLE_PQ_ENVELOPE + clé passerelle), le corps est chiffré de bout en bout
     * vers la passerelle (X25519 + ML-KEM-768). Si l'enveloppe est indisponible
     * ou rejetée par le serveur (400 pq_envelope_*), on repart EN CLAIR : le
     * verdict reste signé et vérifié (hybride) dans tous les cas.
     */
    private fun runAnalyze(
        url: String,
        req: AnalyzeRequest,
        nonce: String,
        keys: KeyManifest.TrustedKeys?,
    ): VerifiedResult {
        val plaintext = json.encodeToString(AnalyzeRequest.serializer(), req)
        // 1) Tentative ENVELOPPÉE (si activée et clé passerelle disponible).
        val gw = gatewayKey()
        if (gw != null) {
            val sealed = try {
                PqEnvelope.seal(plaintext.toByteArray(Charsets.UTF_8), gw)
            } catch (e: Exception) {
                null  // échec de scellement -> repli en clair
            }
            if (sealed != null) {
                val enveloped = executeAnalyze(url, envelopeBody(sealed), nonce, keys, enveloped = true)
                if (enveloped != null) return enveloped
                // null => enveloppe rejetée (400 pq_envelope_*) => repli en clair
            }
        }
        // 2) Chemin EN CLAIR (par défaut, ou repli).
        return executeAnalyze(url, plaintext, nonce, keys, enveloped = false)
            ?: throw AnalysisException("Service indisponible.")
    }
	    /**
     * Variante de [runAnalyze] pour l'analyse profonde : identique (enveloppe PQ
     * + repli clair + vérification hybride du verdict) mais renvoie un
     * DeepOutcome incluant le header X-Deep-Free.
     */
    private fun runAnalyzeDeep(
        url: String,
        req: AnalyzeRequest,
        nonce: String,
        keys: KeyManifest.TrustedKeys?,
    ): DeepOutcome {
        val plaintext = json.encodeToString(AnalyzeRequest.serializer(), req)
        val gw = gatewayKey()
        if (gw != null) {
            val sealed = try {
                PqEnvelope.seal(plaintext.toByteArray(Charsets.UTF_8), gw)
            } catch (e: Exception) {
                null
            }
            if (sealed != null) {
                val enveloped = executeAnalyzeDeep(url, envelopeBody(sealed), nonce, keys, enveloped = true)
                if (enveloped != null) return enveloped
            }
        }
        return executeAnalyzeDeep(url, plaintext, nonce, keys, enveloped = false)
            ?: throw AnalysisException("Service indisponible.")
    }
    /**
     * POST profond + vérification + lecture du header X-Deep-Free. Même
     * sémantique de repli que [executeAnalyze] (null si enveloppe rejetée).
     */
    private fun executeAnalyzeDeep(
        url: String,
        bodyJson: String,
        nonce: String,
        keys: KeyManifest.TrustedKeys?,
        enveloped: Boolean,
    ): DeepOutcome? {
        val httpReq = Request.Builder().url(url)
            .post(bodyJson.toRequestBody(jsonMedia)).build()
        client.newCall(httpReq).execute().use { resp ->
            if (resp.code == 402) throw AnalysisException("pro_required")
            if (resp.code == 429) {
                val body = resp.body?.string().orEmpty()
                throw AnalysisException("quota_exceeded:$body")
            }
            if (enveloped && resp.code == 400) {
                val err = resp.body?.string().orEmpty()
                if (err.contains("pq_envelope")) return null
                throw AnalysisException("Service indisponible (HTTP 400).")
            }
            if (!resp.isSuccessful) throw AnalysisException("Service indisponible (HTTP ${resp.code}).")
            val deepFree = when (resp.header("X-Deep-Free")?.lowercase()) {
                "granted" -> DeepFreeStatus.GRANTED
                "pro" -> DeepFreeStatus.PRO
                else -> DeepFreeStatus.NONE
            }
            val payload = resp.body?.string() ?: throw AnalysisException("Réponse vide du service.")
            val verified = parseVerdict(payload, nonce, keys)
            return DeepOutcome(verified, deepFree)
        }
    }
    /** Sérialise l'enveloppe au format attendu par la passerelle. */
    private fun envelopeBody(sealed: PqEnvelope.Sealed): String =
        buildJsonObject {
            put("ct_mlkem", sealed.ctMlkem)
            put("epk_x25519", sealed.epkX25519)
            put("ct", sealed.ct)
        }.toString()
    /**
     * POST + vérification du verdict signé. Renvoie null UNIQUEMENT si
     * [enveloped] et que le serveur rejette l'enveloppe (400 pq_envelope_*),
     * afin de signaler un repli en clair. Lève AnalysisException sinon
     * (402 pro_required, 429 quota_exceeded, erreurs HTTP, réponse vide).
     */
    private fun executeAnalyze(
        url: String,
        bodyJson: String,
        nonce: String,
        keys: KeyManifest.TrustedKeys?,
        enveloped: Boolean,
    ): VerifiedResult? {
        val httpReq = Request.Builder().url(url)
            .post(bodyJson.toRequestBody(jsonMedia)).build()
        client.newCall(httpReq).execute().use { resp ->
            if (resp.code == 402) throw AnalysisException("pro_required")
            if (resp.code == 429) {
                // Quota dépassé. On remonte une exception RICHE : son message
                // contient le JSON serveur (parsable par le ViewModel pour
                // construire ScanUiState.QuotaExhausted avec reset_at, etc.).
                val body = resp.body?.string().orEmpty()
                throw AnalysisException("quota_exceeded:$body")
            }
            if (enveloped && resp.code == 400) {
                val err = resp.body?.string().orEmpty()
                if (err.contains("pq_envelope")) return null  // repli en clair
                throw AnalysisException("Service indisponible (HTTP 400).")
            }
            if (!resp.isSuccessful) throw AnalysisException("Service indisponible (HTTP ${resp.code}).")
            val payload = resp.body?.string() ?: throw AnalysisException("Réponse vide du service.")
            return parseVerdict(payload, nonce, keys)
        }
    }
    /** Parse + vérifie (hybride) un verdict signé, puis construit le résultat UI. */
    private fun parseVerdict(
        payload: String,
        nonce: String,
        keys: KeyManifest.TrustedKeys?,
    ): VerifiedResult {
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
        // Le rapport voyage comme chaîne canonique (authentifiée en étape 7 de
        // VerdictVerifier). On le parse ensuite pour l'UI.
        val reportDto = try {
            json.decodeFromString(AnalysisReportDto.serializer(), dto.reportCanonical)
        } catch (e: Exception) {
            throw AnalysisException("Rapport illisible ou absent.")
        }
        return VerifiedResult(
            verdict = Verdict.fromWire(dto.verdict),
            score = dto.score,
            report = reportDto,
            signatureVerified = verified,
            rawType = reportDto.payloadType,
        )
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
    /**
     * Vérifie un achat auprès du serveur (qui interroge Google Play) et renvoie
     * le statut Pro + l'entitlement signé à mettre en cache. Le serveur ne stocke
     * aucune identité : vérification transactionnelle du purchaseToken.
     *
     * Note : l'X-Install-Id est injecté automatiquement par HmacInterceptor
     * lorsque l'installation est déjà provisionnée. Le serveur marque alors
     * pro:{install_id} dans Redis (quota 500/jour). Si le provisioning n'est pas
     * encore terminé au moment de l'achat (course achat/provisioning), le
     * marquage est RATTRAPÉ par l'auto-réparation côté serveur dès le 1er scan
     * présentant l'entitlement signé sur /v1/analyze.
     */
    fun verifyPurchase(purchaseToken: String): BillingVerifyResponse {
        val body = json.encodeToString(
            BillingVerifyRequest.serializer(), BillingVerifyRequest(purchaseToken)
        ).toRequestBody(jsonMedia)
        val httpReq = Request.Builder()
            .url("${Config.API_BASE_URL}/v1/billing/verify").post(body).build()
        client.newCall(httpReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw AnalysisException("Vérification d'achat indisponible (HTTP ${resp.code}).")
            }
            val text = resp.body?.string()
                ?: throw AnalysisException("Réponse de vérification vide.")
            return json.decodeFromString(BillingVerifyResponse.serializer(), text)
        }
    }
    /**
     * Vérifie localement un entitlement Pro signé avec les clés DU MANIFESTE
     * (mêmes clés que les verdicts). Sert au gating UI ; le serveur reste
     * l'arbitre réel sur /v1/analyze/deep.
     */
    fun verifyEntitlementToken(token: String): EntitlementVerifier.Result {
        val keys = trustedKeys()
        return EntitlementVerifier.verify(
            token, keys, requirePq = Config.REQUIRE_PQ_VERIFICATION,
        )
    }
}