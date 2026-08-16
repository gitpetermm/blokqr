package com.blokqr.app.crypto

import com.google.crypto.tink.subtle.Ed25519Verify
import org.json.JSONObject
import java.util.Base64

/**
 * Vérifie un jeton d'entitlement Pro signé (émis par /v1/billing/verify).
 *
 * Mêmes garanties qu'un verdict : la signature porte sur la chaîne canonique
 * fournie par le serveur (`canonical`), vérifiée avec les clés DU MANIFESTE
 * (racine SLH-DSA épinglée). Modèle « bearer », sans identité.
 *
 * Contrôles (tous obligatoires) :
 *   1. type == "entitlement" et champs présents ;
 *   2. la clé (kid) correspond à celle du manifeste vérifié ;
 *   3. les champs de tête concordent avec la chaîne canonique signée
 *      (un `canonical` falsifié mais incohérent est rejeté) ;
 *   4. signature Ed25519 valide sur la chaîne canonique ;
 *   5. signature ML-DSA-65 (FIPS 204) valide sur la MÊME chaîne (si requirePq) ;
 *   6. fraîcheur : le jeton n'est pas expiré.
 */
object EntitlementVerifier {

    private const val TYP = "entitlement"
    private val FIELDS = listOf("exp", "iat", "kid", "nonce", "plan", "pro", "typ")

    data class Entitlement(
        val pro: Boolean,
        val plan: String,
        val expiresAt: String,
    )

    data class Result(val entitlement: Entitlement?, val reason: String = "") {
        val ok: Boolean get() = entitlement != null
        /** Vrai uniquement si le jeton est valide, frais ET Pro actif. */
        val isProActive: Boolean get() = entitlement?.pro == true
    }

    fun verify(
        token: String,
        trusted: KeyManifest.TrustedKeys,
        requirePq: Boolean = true,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Result {
        if (token.isBlank()) return Result(null, "Jeton absent.")

        val obj = try {
            JSONObject(token)
        } catch (e: Exception) {
            return Result(null, "Jeton illisible.")
        }

        // 1. Type + champs requis + chaîne canonique présente.
        if (obj.optString("typ") != TYP) return Result(null, "Type de jeton inattendu.")
        for (f in FIELDS) {
            if (!obj.has(f)) return Result(null, "Champ manquant : $f.")
        }
        val canonical = obj.optString("canonical")
        if (canonical.isBlank()) return Result(null, "Chaîne canonique absente.")

        // 2. La clé du jeton doit être celle du manifeste vérifié.
        if (obj.optString("kid") != trusted.keyId) {
            return Result(null, "Identifiant de clé non conforme au manifeste.")
        }

        // 3. Concordance des champs de tête avec la chaîne canonique signée.
        if (!canonicalMatches(obj, canonical)) {
            return Result(null, "Champs incohérents avec la chaîne signée.")
        }

        // 4. Signature Ed25519 avec la clé Ed25519 DU MANIFESTE.
        val msg = canonical.toByteArray(Charsets.UTF_8)
        val edOk = try {
            Ed25519Verify(Base64.getDecoder().decode(trusted.ed25519PubB64))
                .verify(Base64.getDecoder().decode(obj.optString("sig_ed25519")), msg)
            true
        } catch (e: Exception) {
            false
        }
        if (!edOk) return Result(null, "Signature Ed25519 invalide.")

        // 5. Signature ML-DSA-65 avec la clé ML-DSA DU MANIFESTE.
        if (requirePq) {
            val pqSig = obj.optString("sig_mldsa65")
            if (pqSig.isBlank() || trusted.mldsa65PubB64.isBlank()) {
                return Result(null, "Signature post-quantique absente.")
            }
            val pqOk = PqVerifier.verifyMlDsa65(
                Base64.getDecoder().decode(trusted.mldsa65PubB64),
                msg,
                Base64.getDecoder().decode(pqSig),
            )
            if (!pqOk) return Result(null, "Signature ML-DSA-65 invalide.")
        }

        // 6. Fraîcheur (anti-rejeu d'un ancien entitlement).
        val exp = parseIso(obj.optString("exp"))
        if (exp == null || nowEpochSeconds > exp) {
            return Result(null, "Entitlement expiré.")
        }

        return Result(
            Entitlement(
                pro = obj.optBoolean("pro", false),
                plan = obj.optString("plan"),
                expiresAt = obj.optString("exp"),
            )
        )
    }

    /** Les champs de tête doivent égaler ceux de la chaîne canonique signée. */
    private fun canonicalMatches(obj: JSONObject, canonical: String): Boolean {
        return try {
            val c = JSONObject(canonical)
            c.getString("typ") == obj.getString("typ") &&
                c.getBoolean("pro") == obj.getBoolean("pro") &&
                c.getString("plan") == obj.getString("plan") &&
                c.getString("iat") == obj.getString("iat") &&
                c.getString("exp") == obj.getString("exp") &&
                c.getString("kid") == obj.getString("kid") &&
                c.getString("nonce") == obj.getString("nonce")
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
