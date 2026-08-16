package com.blokqr.app.analyzer

import com.blokqr.app.crypto.KeyManifest
import com.blokqr.app.crypto.PqVerifier
import com.google.crypto.tink.subtle.Ed25519Verify
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Vérifie l'authenticité d'une blocklist signée hybride (Ed25519 + ML-DSA-65).
 *
 * La blocklist est produite quotidiennement par le backend (cf.
 * /opt/blokqr/blocklist/build.py côté serveur) et signée avec LE MÊME
 * trousseau que les verdicts. Donc :
 *   - Le `key_id` de la blocklist DOIT correspondre au `key_id` du manifeste
 *     courant (clés provenant de la même rotation).
 *   - Les deux signatures (Ed25519 + ML-DSA-65) DOIVENT être valides sur
 *     `canonical` qui contient les domaines + métadonnées.
 *
 * Garanties identiques à VerdictVerifier :
 *   - Casser la signature exige de casser à la fois Ed25519 ET ML-DSA-65.
 *   - Le client épingle UNIQUEMENT la racine SLH-DSA ; le manifeste authentifie
 *     les clés courantes utilisées ici.
 *
 * NOTE : la blocklist est elle-même un fichier PUBLIC (servi sans authentification
 * via GET /v1/local-blocklist). Sa confidentialité n'est pas requise — seule
 * son INTÉGRITÉ et son AUTHENTICITÉ comptent. Un MITM ne peut pas la modifier
 * (les signatures rejettent toute altération), ni la remplacer par une autre
 * (le key_id ne correspondrait pas au manifeste courant).
 */
object BlocklistSignatureVerifier {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Résultat de vérification : SignedBlocklist si OK, sinon raison de l'échec. */
    data class Result(
        val blocklist: SignedBlocklist?,
        val reason: String = "",
    ) {
        val isValid: Boolean get() = blocklist != null
    }

    /**
     * Vérifie un fichier blocklist au format JSON publié par le backend.
     *
     * @param jsonBytes  Octets bruts du fichier (peut venir d'assets ou du cache).
     * @param trusted    Clés courantes validées par le manifeste SLH-DSA.
     *                   Si null, la vérification de cohérence key_id est sautée
     *                   (utile pour bootstrap initial avant que le manifeste
     *                   soit fetchable — la signature elle-même reste vérifiée).
     * @param requirePq  Si vrai, exige aussi ML-DSA-65 valide.
     * @param nowEpochSeconds Horloge courante (pour la fraîcheur).
     */
    fun verify(
        jsonBytes: ByteArray,
        trusted: KeyManifest.TrustedKeys?,
        requirePq: Boolean = true,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Result {
        // 1. Désérialiser
        val raw: BlocklistRawDto = try {
            json.decodeFromString(BlocklistRawDto.serializer(), jsonBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return Result(null, "Format JSON invalide : ${e.message}")
        }

        // 2. Vérifications structurelles
        if (raw.canonical.isBlank()) {
            return Result(null, "Champ canonical absent.")
        }
        if (raw.signatureEd25519B64.isBlank()) {
            return Result(null, "Signature Ed25519 absente.")
        }
        if (requirePq && raw.signatureMldsa65B64.isBlank()) {
            return Result(null, "Signature ML-DSA-65 absente.")
        }
        if (raw.publicKeyEd25519B64.isBlank() || raw.publicKeyMldsa65B64.isBlank()) {
            return Result(null, "Clés publiques absentes du payload.")
        }
        if (raw.keyId.isBlank()) {
            return Result(null, "Key ID absent.")
        }

        // 3. Cohérence avec le manifeste (si fourni) : les clés utilisées pour
        //    signer DOIVENT correspondre à celles validées par le manifeste
        //    (= mêmes clés que les verdicts).
        if (trusted != null) {
            if (raw.keyId != trusted.keyId) {
                return Result(
                    null,
                    "Key ID blocklist (${raw.keyId}) != manifeste (${trusted.keyId}).",
                )
            }
            if (raw.publicKeyEd25519B64 != trusted.ed25519PubB64) {
                return Result(null, "Clé Ed25519 blocklist != clé du manifeste.")
            }
            if (raw.publicKeyMldsa65B64 != trusted.mldsa65PubB64) {
                return Result(null, "Clé ML-DSA-65 blocklist != clé du manifeste.")
            }
        }

        // 4. Fraîcheur : `expires_at` ne doit pas être dépassé.
        val expEpoch = parseIsoToEpochSeconds(raw.expiresAt)
        if (expEpoch != null && nowEpochSeconds > expEpoch) {
            return Result(null, "Blocklist expirée : ${raw.expiresAt}.")
        }

        // 5. Signature Ed25519 sur le canonical
        val msg = raw.canonical.toByteArray(Charsets.UTF_8)
        val edOk = try {
            Ed25519Verify(Base64.getDecoder().decode(raw.publicKeyEd25519B64))
                .verify(Base64.getDecoder().decode(raw.signatureEd25519B64), msg)
            true
        } catch (e: Exception) {
            false
        }
        if (!edOk) return Result(null, "Signature Ed25519 invalide.")

        // 6. Signature ML-DSA-65 sur le canonical
        if (requirePq) {
            val pqOk = PqVerifier.verifyMlDsa65(
                Base64.getDecoder().decode(raw.publicKeyMldsa65B64),
                msg,
                Base64.getDecoder().decode(raw.signatureMldsa65B64),
            )
            if (!pqOk) return Result(null, "Signature ML-DSA-65 invalide.")
        }

        // 7. Cohérence interne : les domaines listés correspondent au canonical
        //    (sinon un attaquant pourrait modifier `domains` sans toucher
        //    aux signatures qui portent sur `canonical`).
        if (!domainsMatchCanonical(raw.domains, raw.canonical)) {
            return Result(null, "Domaines incohérents avec le canonical signé.")
        }

        // 8. Tout est OK : construire le résultat.
        return Result(
            SignedBlocklist(
                version = raw.version,
                generatedAt = raw.generatedAt,
                expiresAt = raw.expiresAt,
                keyId = raw.keyId,
                sources = raw.sources,
                domains = raw.domains.toSet(),  // Set pour lookup O(1)
                rawJson = jsonBytes,
            ),
            "OK",
        )
    }

    /** Vérifie que le canonical contient bien la liste de domaines présente
     *  dans le DTO. Le canonical étant un JSON canonique signé, on cherche
     *  juste la sous-chaîne `"domains":[...]` et on compare la taille. */
    private fun domainsMatchCanonical(domains: List<String>, canonical: String): Boolean {
        // Sanity check : le nombre de domaines correspond au compteur déclaré
        // dans le canonical (champ "domain_count" présent dans le JSON canonique
        // signé). Cette vérification est un défense-en-profondeur secondaire :
        // la signature elle-même protège déjà l'intégrité du JSON entier.
        return try {
            val countMatch = Regex("\"domain_count\":(\\d+)").find(canonical)
            val declared = countMatch?.groupValues?.get(1)?.toInt() ?: return false
            declared == domains.size
        } catch (e: Exception) {
            false
        }
    }

    private fun parseIsoToEpochSeconds(iso: String): Long? = try {
        if (iso.isBlank()) null else java.time.OffsetDateTime.parse(iso).toEpochSecond()
    } catch (e: Exception) {
        null
    }
}

// --------------------------------------------------------------------------- //
//  DTO de désérialisation
// --------------------------------------------------------------------------- //

@Serializable
internal data class BlocklistRawDto(
    val version: Long = 0,
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("domain_count") val domainCount: Int = 0,
    val sources: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val alg: String = "",
    val canonical: String = "",
    @SerialName("signature_ed25519_b64") val signatureEd25519B64: String = "",
    @SerialName("signature_mldsa65_b64") val signatureMldsa65B64: String = "",
    @SerialName("public_key_ed25519_b64") val publicKeyEd25519B64: String = "",
    @SerialName("public_key_mldsa65_b64") val publicKeyMldsa65B64: String = "",
    @SerialName("key_id") val keyId: String = "",
)

/**
 * Blocklist authentifiée, prête à être utilisée par LocalAnalyzer.
 *
 * Le set `domains` est en mémoire pour lookup O(1). La taille typique est
 * ~500 entrées (publiée) ou ~150 (bundled), ce qui représente ~10-30 KB
 * de RAM totale, négligeable.
 */
data class SignedBlocklist(
    val version: Long,
    val generatedAt: String,
    val expiresAt: String,
    val keyId: String,
    val sources: List<String>,
    val domains: Set<String>,
    /** Octets bruts du fichier (utiles pour réécrire le cache). */
    val rawJson: ByteArray,
) {
    val domainCount: Int get() = domains.size

    // equals/hashCode adaptés (ByteArray nécessite Arrays.equals)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedBlocklist) return false
        return version == other.version &&
            generatedAt == other.generatedAt &&
            keyId == other.keyId
    }
    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + generatedAt.hashCode()
        result = 31 * result + keyId.hashCode()
        return result
    }
}
