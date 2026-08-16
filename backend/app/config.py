"""
Configuration centralisée de BlokQR (backend d'analyse).

Toutes les valeurs sensibles (clés d'API de threat intelligence, clés de
signature) sont injectées par variables d'environnement. Aucun secret n'est
codé en dur, conformément au principe Security by Design.
"""
from __future__ import annotations

from functools import lru_cache
from typing import List

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Paramètres de l'application, chargés depuis l'environnement / .env."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # --- Identité du service -------------------------------------------------
    service_name: str = "blokqr"
    environment: str = Field(default="production")
    debug: bool = Field(default=False)

    # --- Réseau / API --------------------------------------------------------
    host: str = "0.0.0.0"
    port: int = 8000
    # Origines autorisées pour CORS. En production, restreindre au strict besoin.
    cors_allowed_origins: List[str] = Field(default_factory=lambda: ["*"])

    # --- Limitation de débit (anti-abus du service lui-même) -----------------
    rate_limit_per_minute: int = 30

    # --- Résolution des redirections -----------------------------------------
    max_redirect_hops: int = 15
    http_timeout_seconds: float = 8.0
    # Active l'analyse dynamique (navigateur headless). Désactivable si
    # Playwright n'est pas installé : le service reste fonctionnel en mode statique.
    enable_dynamic_sandbox: bool = Field(default=True)
    # Délai par rendu (un profil). Volontairement court : avec domcontentloaded
    # un rendu nominal prend 1–3 s ; cette borne ne mord que sur les pages lentes.
    dynamic_timeout_seconds: float = 8.0
    # Budget TOTAL de l'étape dynamique (les deux profils réunis). Au-delà,
    # l'analyse dynamique est abandonnée (fail-closed) pour garantir une réponse
    # sous le budget réseau du client.
    dynamic_budget_seconds: float = 16.0

    # --- Protection SSRF -----------------------------------------------------
    # Bloque toute résolution vers des plages internes / privées.
    block_private_networks: bool = True
    # Schémas d'URL réseau autorisés à être suivis / analysés.
    allowed_url_schemes: List[str] = Field(default_factory=lambda: ["http", "https"])

    # --- Threat Intelligence (clés API optionnelles) -------------------------
    google_safe_browsing_api_key: str = Field(default="")
    virustotal_api_key: str = Field(default="")
    urlscan_api_key: str = Field(default="")
    # PhishTank et URLhaus sont interrogeables sans clé (rate-limités).
    enable_phishtank: bool = True
    enable_urlhaus: bool = True

    # Délai max accordé à l'ensemble des providers de threat intel.
    threat_intel_timeout_seconds: float = 10.0
    # --- Étage IA (Gemini) — 2e ligne pour les cas ambigus -------------------
    # Sollicité UNIQUEMENT si la threat intel ne liste rien ET qu'un signal reste
    # douteux. N'envoie que des MÉTADONNÉES (URL, redirections, signaux du
    # sandbox), jamais le contenu de page ni la capture. Clé via l'environnement.
    enable_ai_stage: bool = Field(default=False)
    gemini_api_key: str = Field(default="")
    gemini_model: str = Field(default="gemini-3.6-flash")
    # --- Signature des verdicts (anti-falsification du verdict) --------------
    # Clé privée Ed25519 encodée en base64 (32 octets de seed). Générée par
    # scripts/generate_keys.py si absente.
    verdict_signing_seed_b64: str = Field(default="")
    # Trousseau de verdict persisté (Ed25519 + ML-DSA-65) pour un key_id stable
    # entre redémarrages. Généré au premier lancement si absent.
    verdict_key_path: str = Field(default="keys/verdict_keys.json")
    # Active la signature post-quantique ML-DSA-65 si liboqs est disponible.
    enable_pq_signature: bool = True
    # --- Politique de notation -----------------------------------------------
    # Seuils de score (0 = sûr, 100 = critique).
    #   score >= dangerous  -> au moins DANGEROUS (suspect)
    #   score >= malicious  -> MALICIOUS (menace confirmée)
    score_threshold_dangerous: int = 35
    score_threshold_malicious: int = 70

    # --- Double prévisualisation ---------------------------------------------
    # Capture d'écran de la page finale (PNG) renvoyée au client. L'analyse
    # d'usurpation par IA tourne ensuite SUR L'APPAREIL (le contenu de la page
    # n'est jamais transmis à un tiers).
    enable_screenshot: bool = True
    screenshot_max_width: int = 720

    # --- Vie privée -----------------------------------------------------------
    # Levier principal (toujours actif) : le palier de réputation ne reçoit que
    # des PRÉFIXES de hash (k-anonymat) — le serveur n'apprend jamais l'URL.
    # Minimisation d'IP : ne pas journaliser l'adresse du client (défense par
    # configuration ; suffisante pour démarrer sans relais).
    log_client_ip: bool = False
    # OPTIONNEL / phase 2 : placer le service derrière un relais Oblivious HTTP
    # (RFC 9458) opéré par un TIERS INDÉPENDANT (Fastly/Cloudflare) pour masquer
    # aussi l'IP. Sans tiers indépendant, OHTTP n'apporte AUCUN bénéfice : ne pas
    # l'auto-héberger. Voir le guide de déploiement, section confidentialité.
    behind_ohttp_relay: bool = False

    # --- Racine de confiance post-quantique (SLH-DSA) ------------------------
    # Clé racine STABLE signant le manifeste de clés. En production : HSM/KMS.
    sphincs_root_key_path: str = Field(default="keys/slhdsa_root.json")
    enable_pq_envelope: bool = Field(default=True)  # enveloppe ML-KEM des requêtes
    trust_forwarded_for: bool = Field(default=True)
    # --- Fraîcheur et politique d'échec --------------------------------------
    # Durée de validité d'un verdict (anti-rejeu d'un ancien « safe »).
    verdict_ttl_seconds: int = 120
    # Politique en cas d'impossibilité d'analyser : True = fail-closed (refuser
    # l'ouverture, prudence), recommandé pour un outil de sécurité.
    fail_closed: bool = True
    overall_budget_seconds: float = 20.0
    # --- Durcissement du bac à sable -----------------------------------------
    # Sortie réseau via proxy (idéalement résidentiel/mobile) pour ne pas être
    # classé « datacenter » par les pages détonées.
    egress_proxy_url: str = Field(default="")
    # NE PAS activer sauf environnement sans isolation externe (gVisor/Firecracker).
    sandbox_disable_chromium_sandbox: bool = Field(default=False)
    # Toujours lancer le second profil (sinon escalade seulement si suspicion).
    always_dual_profile: bool = Field(default=False)

    # --- Réputation ----------------------------------------------------------
    # Fichier de flux additionnel (URLhaus/PhishTank exporté), optionnel.
    threat_feed_path: str = Field(default="")

    # --- Cache d'analyse -----------------------------------------------------
    analysis_cache_ttl_seconds: int = 300
    ai_cache_ttl_seconds: int = Field(default=21600)
# ======================================================================= #
    #  Réglages requis par le code (réalignement config <-> code)             #
    # ======================================================================= #

    # --- Threat intelligence : clés/paramètres supplémentaires ---------------
    web_risk_api_key: str = Field(default="")          # certains modules lisent ce nom
    abuseipdb_api_key: str = Field(default="")
    urlhaus_auth_key: str = Field(default="")

    # --- Alias de bascules (mêmes booléens, autre nom lu par le code) --------

    # --- Budgets de temps ----------------------------------------------------
    overall_budget_seconds: float = Field(default=20.0)
    redirect_budget_seconds: float = Field(default=8.0)
    domain_age_timeout_seconds: float = Field(default=4.0)
    domain_age_cache_ttl_seconds: int = Field(default=86400)

    # --- Cache d'analyse -----------------------------------------------------
    enable_analysis_cache: bool = Field(default=True)
    analysis_cache_max_entries: int = Field(default=2000)

    # --- WHOIS / RDAP (âge de domaine) ---------------------------------------
    rdap_base_url: str = Field(default="https://rdap.org/domain/")

    # --- Anti-abus : IP suivies ---------------------------------------------
    rate_limit_max_tracked_ips: int = Field(default=10000)

    # --- HMAC (authentification requête app) ---------------------------------
    require_hmac: bool = Field(default=True)
    hmac_window_seconds: int = Field(default=300)

    # --- Play Integrity (anti-falsification client) --------------------------
    require_integrity: bool = Field(default=False)
    integrity_allow_unlicensed: bool = Field(default=True)
    integrity_max_age_seconds: int = Field(default=300)
    integrity_min_device_verdict: str = Field(default="MEETS_BASIC_INTEGRITY")

    # --- Quotas (gratuit / profond / Pro) ------------------------------------
    free_daily_quota: int = Field(default=7)
    free_deep_daily_quota: int = Field(default=1)
    pro_daily_quota: int = Field(default=500)

    # --- Facturation / abonnements Google Play -------------------------------
    enable_billing_verify: bool = Field(default=False)
    entitlement_ttl_seconds: int = Field(default=2592000)   # 30 jours
    android_package_name: str = Field(default="com.blokqr.app")
    play_package_name: str = Field(default="com.blokqr.app")
    play_api_base: str = Field(default="https://androidpublisher.googleapis.com")
    play_api_timeout_seconds: float = Field(default=10.0)
    play_service_account_path: str = Field(default="secrets/play-service-account.json")

    # --- Chemins de clés (passerelle) ----------------------------------------
    gateway_key_path: str = Field(default="keys/gateway_keys.json")

@lru_cache
def get_settings() -> Settings:
    """Retourne une instance unique et mise en cache des paramètres."""
    return Settings()
