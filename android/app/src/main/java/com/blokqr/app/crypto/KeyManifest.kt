package com.blokqr.app.crypto

import com.blokqr.app.model.KeyManifestDto
import java.util.Base64

/**
 * Racine de confiance et rotation de clés.
 *
 * Le client n'épingle QUE la clé publique racine SLH-DSA (Config). Il récupère
 * le manifeste signé, vérifie sa signature SLH-DSA contre la racine épinglée,
 * contrôle sa fraîcheur, puis fait confiance aux clés de verdict (Ed25519 +
 * ML-DSA-65) et à la clé de passerelle (ML-KEM) qu'il publie. La rotation des
 * clés de verdict ne nécessite donc aucune mise à jour de l'application.
 */
object KeyManifest {

    /** Clés courantes validées, extraites d'un manifeste de confiance. */
    data class TrustedKeys(
        val keyId: String,
        val ed25519PubB64: String,
        val mldsa65PubB64: String,
        val mlkem768PubB64: String,
    )

    data class Result(val keys: TrustedKeys?, val reason: String = "")

    /**
     * Vérifie un manifeste contre la racine SLH-DSA épinglée.
     *
     * @param manifest          manifeste renvoyé par GET /manifest
     * @param pinnedRootB64     clé publique racine SLH-DSA épinglée (Config)
     * @param nowEpochSeconds   horloge courante (pour la fraîcheur)
     */
    fun verify(
        manifest: KeyManifestDto,
        pinnedRootB64: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Result {
        if (pinnedRootB64.isBlank()) {
            return Result(null, "Racine SLH-DSA non épinglée dans l'application.")
        }
        // La racine servie doit correspondre à la racine épinglée (anti-substitution).
        if (manifest.rootPubB64.isNotBlank() && manifest.rootPubB64 != pinnedRootB64) {
            return Result(null, "Racine du manifeste différente de la racine épinglée.")
        }

        val sigOk = try {
            PqVerifier.verifySlhDsa128s(
                Base64.getDecoder().decode(pinnedRootB64),
                manifest.canonical.toByteArray(Charsets.UTF_8),
                Base64.getDecoder().decode(manifest.sigSlhdsaB64),
            )
        } catch (e: Exception) {
            false
        }
        if (!sigOk) return Result(null, "Signature SLH-DSA du manifeste invalide.")

        // Fraîcheur : le manifeste ne doit pas être expiré.
        val notAfter = parseIsoToEpochSeconds(manifest.notAfter)
        if (notAfter != null && nowEpochSeconds > notAfter) {
            return Result(null, "Manifeste expiré (rotation requise).")
        }

        return Result(
            TrustedKeys(
                keyId = manifest.keyId,
                ed25519PubB64 = manifest.ed25519PubB64,
                mldsa65PubB64 = manifest.mldsa65PubB64,
                mlkem768PubB64 = manifest.mlkem768PubB64,
            )
        )
    }

    private fun parseIsoToEpochSeconds(iso: String): Long? = try {
        java.time.OffsetDateTime.parse(iso).toEpochSecond()
    } catch (e: Exception) {
        null
    }
}
