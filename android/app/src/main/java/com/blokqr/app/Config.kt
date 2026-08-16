package com.blokqr.app
/**
 * Configuration applicative.
 *
 * Valeurs d'épinglage (PUBLIQUES, OK à committer) :
 *   - API_BASE_URL : URL publique HTTPS du service BlokQR.
 *   - PINNED_SLHDSA_ROOT_PUBKEY_B64 : clé publique RACINE SLH-DSA (FIPS 205),
 *     obtenue via GET /manifest (champ root_pub_b64) puis figée ici. C'est le
 *     SEUL élément cryptographique épinglé : les clés de verdict (Ed25519 +
 *     ML-DSA-65) tournent librement via le manifeste signé, sans mise à jour
 *     de l'application.
 *   - CERT_PIN_SHA256 : empreinte SPKI du certificat TLS (épinglage OkHttp).
 *
 * Ne JAMAIS écraser ce fichier en extrayant l'archive : il porte les valeurs
 * propres à votre déploiement.
 */
object Config {
    const val API_BASE_URL: String = "https://api.blokqr.com"
    const val PRIVACY_POLICY_URL: String = "https://blokqr.com/confidentialite"
    const val PLAY_STORE_URL: String = "https://play.google.com/store/apps/details?id=com.blokqr.app"
    const val CONTACT_EMAIL: String = "contact@blokqr.com"
    /**
     * Base des guides d'utilisation hébergés en ligne (PDF par langue).
     * L'écran « À propos » ouvre "$USER_GUIDE_BASE_URL/<code>.pdf" selon la
     * langue ACTIVE de l'app (fr, en, es, pt, it, de, ar, tr, hi, zh, ja, ru),
     * avec repli sur "en". Héberger les 12 fichiers sous ces noms exacts.
     */
    const val USER_GUIDE_BASE_URL: String = "https://blokqr.com/guide"
	// Racine de confiance SLH-DSA-SHA2-128s (base64).
    const val PINNED_SLHDSA_ROOT_PUBKEY_B64: String =
        "5KMTvl5Q2Q5TUhHqziw9z4Dz3xEFk3RXv6/G1jRfk7A="
    // Épinglage de certificat TLS (format OkHttp "sha256/BASE64...").
    // Strategie : on epingle uniquement les ANCRES STABLES (intermediaire
    // Let's Encrypt + racines ISRG), PAS la feuille. Caddy regenere la cle
    // feuille a chaque renouvellement (~60 j) : l'epingler imposerait une mise
    // a jour bimestrielle de l'app, sans gain reel (un attaquant devrait deja
    // avoir obtenu un vrai certificat Let's Encrypt pour le domaine). Les ancres
    // ci-dessous durent des annees et couvrent RSA (X1) comme ECDSA (X2).
    // CERT_PIN_SHA256 sert de sentinelle on/off (un suffixe "AAA=" desactive
    // l'epinglage) : il pointe sur l'intermediaire stable, jamais sur la feuille.
    // OkHttp valide sur la chaine complete : tant qu'une ancre epinglee y figure,
    // un changement de feuille/intermediaire ne casse rien.
    // Pour un pinning STRICT de la feuille, il faudrait fixer la cle cote Caddy
    // (reutilisation de cle au renouvellement) puis reintroduire la feuille ici.
    const val CERT_PIN_SHA256: String =
        "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y="
    val CERT_PINS_SHA256: List<String> = listOf(
        "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=", // intermediaire Let's Encrypt YE2
        "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // ISRG Root X1 (ancre stable, RSA)
        "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", // ISRG Root X2 (ancre stable, ECDSA)
    )
    // Exiger une signature de verdict valide (hybride) : sinon résultat rejeté.
    const val REQUIRE_SIGNED_VERDICT: Boolean = true
    // Exiger la vérification post-quantique ML-DSA-65 (en plus d'Ed25519).
    const val REQUIRE_PQ_VERIFICATION: Boolean = true
    // Chiffrer le corps des requêtes via l'enveloppe hybride ML-KEM + X25519.
    val ENABLE_PQ_ENVELOPE: Boolean = true
    // Délais réseau (marge pour les cibles lentes ; le serveur borne sa durée).
    const val NETWORK_TIMEOUT_SECONDS: Long = 35
}