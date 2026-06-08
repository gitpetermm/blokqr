package com.blokqr.app.crypto

import com.blokqr.app.model.GatewayKeyDto
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import java.util.Base64

/**
 * Enveloppe de confidentialité hybride (X25519 + ML-KEM-768), côté client.
 *
 * Répond à la confidentialité « harvest-now, decrypt-later » : le corps de la
 * requête (ex. préfixes de hash de réputation) est chiffré de bout en bout vers
 * la passerelle, en complément du relais OHTTP et indépendamment de TLS. Un
 * adversaire devrait casser X25519 ET ML-KEM-768 pour le déchiffrer.
 *
 * Le secret partagé combine l'encapsulation ML-KEM et un échange X25519
 * éphémère, dérivés via HKDF-SHA256, puis utilisés en ChaCha20-Poly1305.
 * (Schéma symétrique à celui de la passerelle ; voir backend pq_envelope.py.)
 */
object PqEnvelope {

    data class Sealed(val ctMlkem: String, val epkX25519: String, val ct: String)

    fun seal(plaintext: ByteArray, gateway: GatewayKeyDto): Sealed {
        val rng = SecureRandom()

        // 1. Encapsulation ML-KEM-768 -> (ciphertext, secret post-quantique).
        val kemPub = MLKEMPublicKeyParameters(
            MLKEMParameters.ml_kem_768,
            Base64.getDecoder().decode(gateway.mlkem768Pub),
        )
        val gen = MLKEMGenerator(rng)
        val enc = gen.generateEncapsulated(kemPub)
        val ssPq = enc.secret
        val ctPq = enc.encapsulation

        // 2. Échange X25519 éphémère -> secret classique.
        val kpg = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(rng)) }
        val kp = kpg.generateKeyPair()
        val ephPriv = kp.private as X25519PrivateKeyParameters
        val ephPub = kp.public as X25519PublicKeyParameters
        val gwX = X25519PublicKeyParameters(Base64.getDecoder().decode(gateway.x25519Pub), 0)
        val ssCl = ByteArray(32)
        X25519Agreement().apply { init(ephPriv) }.calculateAgreement(gwX, ssCl, 0)

        // 3. Clé = HKDF-SHA256(ssPq || ssCl), info dédiée.
        val key = Hkdf.derive(ssPq + ssCl, info = "blokqr/pq-envelope/v1".toByteArray())

        // 4. AEAD ChaCha20-Poly1305 (nonce nul : clé unique par enveloppe).
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"),
            IvParameterSpec(ByteArray(12)))
        val ct = cipher.doFinal(plaintext)

        val b64 = Base64.getEncoder()
        return Sealed(b64.encodeToString(ctPq), b64.encodeToString(ephPub.encoded),
            b64.encodeToString(ct))
    }
}

/** HKDF-SHA256 minimal (extract+expand) pour la dérivation de clé d'enveloppe. */
private object Hkdf {
    fun derive(ikm: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        // extract (salt nul)
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // expand
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01)
        return mac.doFinal().copyOf(length)
    }
}
