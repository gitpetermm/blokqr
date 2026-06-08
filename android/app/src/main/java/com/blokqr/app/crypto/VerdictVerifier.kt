package com.blokqr.app.crypto

import com.google.crypto.tink.subtle.Ed25519Verify
import com.blokqr.app.model.SignedVerdictDto
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * Vérifie l'authenticité d'un verdict signé (chaîne hybride post-quantique).
 *
 * Contrôles (tous obligatoires si REQUIRE_SIGNED_VERDICT) :
 *   1. les clés utilisées proviennent du MANIFESTE vérifié par la racine SLH-DSA
 *      épinglée (et non d'une clé livrée avec le verdict) ;
 *   2. signature Ed25519 valide sur le payload canonique ;
 *   3. signature ML-DSA-65 (FIPS 204) valide sur le MÊME payload — les deux sont
 *      requises : forger un verdict exige de casser le classique ET le post-quantique ;
 *   4. cohérence du payload canonique avec verdict/score/nonce/horodatages affichés ;
 *   5. fraîcheur : le verdict n'est pas expiré (anti-rejeu d'un ancien « safe ») ;
 *   6. nonce conforme à la requête ;
 *   7. liaison du rapport : le hash signé `report_sha256` est présent dans la
 *      chaîne canonique (la capture et les raisons sont ainsi authentifiées).
 */
object VerdictVerifier {

    data class Outcome(val verified: Boolean, val reason: String = "")

    fun verify(
        dto: SignedVerdictDto,
        expectedNonce: String,
        trusted: KeyManifest.TrustedKeys,
        requirePq: Boolean = true,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Outcome {
        // 6. Nonce (anti-rejeu inter-requête).
        if (dto.clientNonce != expectedNonce) {
            return Outcome(false, "Nonce incohérent (possible rejeu).")
        }

        // 4. Cohérence du payload canonique avec les champs affichés.
        if (!canonicalMatches(dto)) {
            return Outcome(false, "Payload signé incohérent avec le verdict affiché.")
        }

        // 5. Fraîcheur du verdict.
        val exp = parseIso(dto.expiresAt)
        if (exp != null && nowEpochSeconds > exp) {
            return Outcome(false, "Verdict expiré : relancez l'analyse.")
        }

        // 1+2. Signature Ed25519 avec la clé Ed25519 DU MANIFESTE.
        val msg = dto.canonicalPayload.toByteArray(Charsets.UTF_8)
        val edOk = try {
            Ed25519Verify(Base64.getDecoder().decode(trusted.ed25519PubB64))
                .verify(Base64.getDecoder().decode(dto.signatureEd25519B64), msg)
            true
        } catch (e: Exception) {
            false
        }
        if (!edOk) return Outcome(false, "Signature Ed25519 invalide.")

        // 1+3. Signature ML-DSA-65 avec la clé ML-DSA DU MANIFESTE.
        if (requirePq) {
            if (dto.signatureMldsa65B64.isBlank() || trusted.mldsa65PubB64.isBlank()) {
                return Outcome(false, "Signature post-quantique absente.")
            }
            val pqOk = PqVerifier.verifyMlDsa65(
                Base64.getDecoder().decode(trusted.mldsa65PubB64),
                msg,
                Base64.getDecoder().decode(dto.signatureMldsa65B64),
            )
            if (!pqOk) return Outcome(false, "Signature ML-DSA-65 invalide.")
        }

        // 7. Liaison du rapport : report_sha256 doit être présent et signé.
        if (dto.reportSha256.isBlank()) {
            return Outcome(false, "Liaison du rapport absente (report_sha256).")
        }

        return Outcome(true)
    }

    private fun canonicalMatches(dto: SignedVerdictDto): Boolean {
        return try {
            val obj = JSONObject(dto.canonicalPayload)
            obj.getString("v") == dto.verdict &&
                obj.getInt("score") == dto.score &&
                obj.getString("nonce") == dto.clientNonce &&
                obj.getString("issued_at") == dto.issuedAt &&
                obj.getString("expires_at") == dto.expiresAt &&
                obj.getString("report_sha256") == dto.reportSha256 &&
                obj.getString("key_id") == dto.keyId
        } catch (e: Exception) {
            false
        }
    }

    private fun parseIso(iso: String): Long? = try {
        if (iso.isBlank()) null else java.time.OffsetDateTime.parse(iso).toEpochSecond()
    } catch (e: Exception) {
        null
    }
}
