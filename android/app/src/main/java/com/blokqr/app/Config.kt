package com.blokqr.app

/**
 * Configuration applicative.
 *
 * À renseigner avant compilation :
 *   - API_BASE_URL : URL publique HTTPS du service BlokQR (ou du relais OHTTP).
 *   - PINNED_SLHDSA_ROOT_PUBKEY_B64 : clé publique RACINE SLH-DSA (FIPS 205),
 *     obtenue via GET /manifest (champ root_pub_b64) lors d'un bootstrap de
 *     confiance, puis figée ici. C'est le SEUL élément cryptographique épinglé :
 *     les clés de verdict (Ed25519 + ML-DSA-65) tournent librement via le
 *     manifeste signé, sans mise à jour de l'application.
 *   - CERT_PIN_SHA256 : empreinte SPKI du certificat TLS (épinglage OkHttp).
 *
 * Pourquoi épingler la racine SLH-DSA et non la clé de verdict ? Parce qu'une
 * racine hachée (post-quantique conservatrice) signée rarement est l'ancre
 * idéale : compromettre un verdict exigerait de forger une signature SLH-DSA.
 */
object Config {

    const val API_BASE_URL: String = "https://api.blokqr.com"

    // Racine de confiance SLH-DSA-SHA2-128s (base64). À remplacer après bootstrap.
    const val PINNED_SLHDSA_ROOT_PUBKEY_B64: String = "3ZRQzdk88rrhbP61ntLrhjMV/WJSx5sFrYl3eVK0F+c="

    // Épinglage de certificat TLS (format OkHttp "sha256/BASE64..."). À remplacer.
    const val CERT_PIN_SHA256: String = "sha256/SYQy1hJ8OvmVI3x+c8DEhQwMXVfSek3gM9xU2mVwCf0="

    // Exiger une signature de verdict valide (hybride) : sinon résultat rejeté.
    const val REQUIRE_SIGNED_VERDICT: Boolean = true

    // Exiger la vérification post-quantique ML-DSA-65 (en plus d'Ed25519).
    // À valider une fois l'interopérabilité d'encodage confirmée sur l'appareil.
    const val REQUIRE_PQ_VERIFICATION: Boolean = true

    // Chiffrer le corps des requêtes via l'enveloppe hybride ML-KEM + X25519.
    const val ENABLE_PQ_ENVELOPE: Boolean = false

    // Délais réseau.
    const val NETWORK_TIMEOUT_SECONDS: Long = 25
}
