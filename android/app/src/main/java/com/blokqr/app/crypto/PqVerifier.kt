package com.blokqr.app.crypto

import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import org.bouncycastle.pqc.crypto.sphincsplus.SPHINCSPlusParameters
import org.bouncycastle.pqc.crypto.sphincsplus.SPHINCSPlusPublicKeyParameters
import org.bouncycastle.pqc.crypto.sphincsplus.SPHINCSPlusSigner

/**
 * Vérification post-quantique réelle sur l'appareil.
 *
 * - ML-DSA-65 (FIPS 204) : seconde signature de chaque verdict (hybride Ed25519).
 *   Côté serveur : pqcrypto ml_dsa_65 ; côté app : MLDSASigner (interface Signer,
 *   en flux : update + verifySignature(signature)). Interop validée.
 * - SPHINCS+-SHA2-128s « simple » : signature de la racine de confiance (manifeste).
 *   Côté serveur : pqcrypto sphincs_sha2_128s_simple (spec round-3) ; côté app :
 *   SPHINCSPlusSigner round-3 — et NON SLHDSASigner (FIPS 205, qui préfixe un
 *   séparateur de domaine + contexte et ne serait donc pas compatible). Interop validée.
 */
object PqVerifier {

    /** Vérifie une signature ML-DSA-65 (FIPS 204) sur `message`. */
    fun verifyMlDsa65(publicKeyRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            val pub = MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, publicKeyRaw)
            val signer = MLDSASigner()
            signer.init(false, pub)
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    /** Vérifie une signature SPHINCS+-SHA2-128s « simple » sur `message`. */
    fun verifySlhDsa128s(publicKeyRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            val pub = SPHINCSPlusPublicKeyParameters(SPHINCSPlusParameters.sha2_128s, publicKeyRaw)
            val signer = SPHINCSPlusSigner()
            signer.init(false, pub)
            signer.verifySignature(message, signature)
        } catch (_: Exception) {
            false
        }
    }
}