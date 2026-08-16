// L'API « légère » PQC de BouncyCastle (MLDSASigner / SLHDSASigner et leurs
// Parameters) est marquée @Deprecated depuis BC 1.84 au profit de l'API JCA,
// mais reste pleinement fonctionnelle. On la conserve DÉLIBÉRÉMENT : ce chemin
// exact est validé par vecteurs de test (KAT) pour l'interop FIPS 204/205 avec
// le serveur (signatures « pures », contexte vide). Migrer vers l'API JCA
// imposerait de re-valider l'interop byte-à-byte pour aucun gain fonctionnel.
@file:Suppress("DEPRECATION")

package com.blokqr.app.crypto

import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import org.bouncycastle.pqc.crypto.slhdsa.SLHDSAParameters
import org.bouncycastle.pqc.crypto.slhdsa.SLHDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.slhdsa.SLHDSASigner

/**
 * Vérification post-quantique réelle sur l'appareil (FIPS 204 / FIPS 205).
 *
 * - ML-DSA-65  : seconde signature de chaque verdict (hybride avec Ed25519).
 * - SLH-DSA-SHA2-128s : signature de la racine de confiance (manifeste de clés).
 *
 * Les clés publiques et signatures sont au format brut normatif FIPS, tel que
 * produit par la bibliothèque serveur (ML-DSA via PQClean, SLH-DSA via slh-dsa).
 * L'interopérabilité serveur <-> BouncyCastle 1.84 est validée par KAT.
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

    /** Vérifie une signature SLH-DSA-SHA2-128s (FIPS 205) sur `message`. */
    fun verifySlhDsa128s(publicKeyRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            // Vraie SLH-DSA FIPS 205 : le serveur signe en mode « pur » (sign_pure,
            // contexte vide) via la lib slh-dsa ; BouncyCastle applique lui-même le
            // séparateur de domaine, on lui passe donc le message BRUT.
            // Interop serveur (FIPS 205) <-> BC 1.84 SLHDSASigner validée par KAT.
            val pub = SLHDSAPublicKeyParameters(SLHDSAParameters.sha2_128s, publicKeyRaw)
            val signer = SLHDSASigner()
            signer.init(false, pub)
            signer.verifySignature(message, signature)
        } catch (_: Exception) {
            false
        }
    }
}