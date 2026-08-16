package com.blokqr.app.model

/**
 * Raison pour laquelle l'application a basculé sur l'analyse LOCALE dégradée
 * (LocalAnalyzer) au lieu d'obtenir un verdict signé du serveur.
 *
 * Sert uniquement à afficher un bandeau contextuel honnête à l'utilisateur :
 * dans TOUS ces cas, le verdict n'est PAS vérifié par le serveur (garde-fou
 * de sécurité). La raison ne change rien à la rigueur du verdict local — elle
 * explique seulement pourquoi on est en mode dégradé.
 */
enum class LocalReason {

    /** Pas de réseau (DNS, connexion, timeout, TLS). Hors-ligne ou avion. */
    OFFLINE,

    /**
     * L'installation n'est pas encore (ou plus) reconnue du serveur :
     * provisionnement en cours, ou éviction côté serveur (401 install_unknown).
     * Une réparation est tentée en arrière-plan ; un nouveau scan en ligne
     * redonnera un verdict signé.
     */
    INITIALIZING,

    /** Le service d'analyse est momentanément indisponible (erreur 5xx). */
    SERVER_UNAVAILABLE,

    /** Quota quotidien gratuit atteint : l'utilisateur continue en local. */
    QUOTA_EXHAUSTED,
}