"""
Schémas de données (contrats d'API) de BlokQR.

Définit la forme des requêtes et des réponses. Les verdicts sont typés et
signés pour empêcher toute altération en transit.
"""
from __future__ import annotations

from enum import Enum
from typing import Annotated, List, Optional

from pydantic import BaseModel, Field


class PayloadType(str, Enum):
    """Type de contenu décodé depuis un QR / code-barres."""

    URL = "url"
    DEEP_LINK = "deep_link"
    WIFI = "wifi"
    VCARD = "vcard"
    MECARD = "mecard"
    EMAIL = "email"
    PHONE = "phone"
    SMS = "sms"
    GEO = "geo"
    CRYPTO = "crypto"          # ex. bitcoin:, ethereum:
    CALENDAR = "calendar"      # vEvent
    TEXT = "text"
    UNKNOWN = "unknown"


class Verdict(str, Enum):
    """Verdict final rendu à l'utilisateur.

    Trois paliers, alignés sur l'interface mobile :
      - SAFE       (vert   #00C853) : aucune menace détectée.
      - DANGEROUS  (ambre  #FFAB00) : lien suspect (redirections, imitation) —
                                       ouverture bloquée, forçable en bac à sable.
      - MALICIOUS  (rouge  #D50000) : menace confirmée — aucune ouverture possible.
    """

    SAFE = "safe"
    DANGEROUS = "dangerous"
    MALICIOUS = "malicious"
    UNKNOWN = "unknown"


class Severity(str, Enum):
    """Sévérité d'un signal individuel."""

    INFO = "info"
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


# --------------------------------------------------------------------------- #
#  Requête
# --------------------------------------------------------------------------- #
class AnalyzeRequest(BaseModel):
    """Requête d'analyse envoyée par l'application mobile.

    Le téléphone n'ouvre JAMAIS la cible : il transmet uniquement la chaîne
    brute décodée localement. Toute opération risquée est déportée ici.
    """

    raw_payload: str = Field(
        ...,
        min_length=1,
        max_length=8192,
        description="Contenu brut décodé localement par le scanner.",
    )
    symbology: Optional[str] = Field(
        default=None,
        description="Type de symbologie détecté par le scanner (qr, ean13, ...).",
    )
    # Nonce client : ré-émis dans la réponse signée pour lier verdict <-> requête
    # et empêcher le rejeu (replay) d'un ancien verdict 'safe'.
    client_nonce: str = Field(
        ...,
        min_length=8,
        max_length=128,
        description="Nonce aléatoire généré par le client.",
    )
    # --- Contexte temporel / géographique (optionnel, vie privée) -----------
    # Dernière destination connue par l'appareil pour cette source (hash BLAKE3
    # de l'hôte). Permet de détecter un QR dynamique sans conserver d'URL.
    prior_destination_hash: Optional[str] = Field(
        default=None, max_length=128,
        description="Hash de la dernière destination observée localement.",
    )
    # Demande la capture d'écran de la page finale (double prévisualisation).
    # Geohash grossier (~4 caracteres, precision ville/region). Jamais de
    # position precise. Utilise pour la detection de cloaking geographique.
    coarse_geohash: Optional[str] = Field(
        default=None, max_length=12,
        description="Geohash tronque pour la detection de cloaking geographique.",
    )
    want_screenshot: bool = Field(
        default=True,
        description="Capturer un aperçu de la page finale en bac à sable.",
    )
    # Consentement explicite à l'analyse profonde d'une URL personnelle (capability).
    consent_deep_analysis: bool = Field(
        default=False,
        description="Autorise l'analyse profonde même si l'URL semble porter un jeton.",
    )
    # Hash de la source scannée (pour la corrélation de consensus communautaire).
    source_hash: Optional[str] = Field(
        default=None, max_length=128,
        description="Hash de la source (corrélation de consensus k-anonyme).",
    )
    # Jeton d'entitlement Pro signé (requis pour /v1/analyze/deep). Émis par
    # /v1/billing/verify, mis en cache par le client et présenté tel quel.
    entitlement: Optional[str] = Field(
        default=None, max_length=12000,
        description="Jeton d'entitlement Pro signé (palier profond / Pro).",
    )


# --------------------------------------------------------------------------- #
#  Composants de la réponse
# --------------------------------------------------------------------------- #
class Signal(BaseModel):
    """Indicateur unitaire détecté par un analyseur."""

    code: str = Field(..., description="Identifiant machine du signal.")
    title: str = Field(..., description="Libellé lisible.")
    detail: str = Field(default="", description="Explication détaillée.")
    severity: Severity = Severity.INFO
    weight: int = Field(default=0, description="Poids contribué au score.")
    source: str = Field(default="", description="Analyseur émetteur.")


class RedirectHop(BaseModel):
    """Un saut dans la chaîne de redirection."""

    index: int
    url: str
    status_code: Optional[int] = None
    method: str = "GET"
    kind: str = Field(default="http", description="http | meta-refresh | js | final")
    resolved_ip: Optional[str] = None
    server: Optional[str] = None


class ThreatIntelResult(BaseModel):
    """Verdict d'un fournisseur de threat intelligence."""

    provider: str
    malicious: bool = False
    available: bool = True
    categories: List[str] = Field(default_factory=list)
    detail: str = ""
    raw_score: Optional[float] = None


class AnalysisReport(BaseModel):
    """Rapport d'analyse complet (cœur du verdict)."""

    payload_type: PayloadType
    displayed_value: str = Field(
        ..., description="Valeur lisible (URL finale, SSID Wi-Fi, etc.)."
    )
    original_target: Optional[str] = Field(
        default=None, description="Première URL avant redirections."
    )
    final_target: Optional[str] = Field(
        default=None, description="Destination finale après toutes redirections."
    )
    redirect_chain: List[RedirectHop] = Field(default_factory=list)
    threat_intel: List[ThreatIntelResult] = Field(default_factory=list)
    signals: List[Signal] = Field(default_factory=list)
    cloaking_detected: bool = False
    login_page_impersonation: Optional[str] = Field(
        default=None, description="Marque usurpée détectée le cas échéant."
    )
    qrljacking_suspected: bool = False
    destination_changed: bool = Field(
        default=False,
        description="La destination diffère de celle observée précédemment.",
    )
    # Raisons en langage clair (alimentent la double prévisualisation pédagogique).
    reasons: List[str] = Field(default_factory=list)
    # Aperçu de la page finale capturé en bac à sable (PNG base64, optionnel).
    # L'analyse d'usurpation par IA s'exécute sur l'APPAREIL à partir de cet aperçu.
    screenshot_b64: Optional[str] = Field(default=None)
    # Hash de la destination actuelle, à mémoriser par l'appareil pour le
    # prochain contrôle de contexte temporel.
    current_destination_hash: Optional[str] = Field(default=None)
    # Domaine enregistrable (eTLD+1) de la destination finale.
    domain_registrable: Optional[str] = Field(default=None)
    # L'URL semble porter un jeton personnel (capability) : vie privée.
    capability_url: bool = Field(default=False)
    # Analyse profonde suspendue (capability sans consentement).
    privacy_hold: bool = Field(default=False)
    # Mur anti-robot détecté (technique d'évasion d'analyse).
    gating_detected: bool = Field(default=False)
    # La destination diverge du consensus communautaire.
    diverges_consensus: bool = Field(default=False)


class SignedVerdict(BaseModel):
    """Enveloppe signée renvoyée au client.

    La signature couvre le canonical_payload (verdict + score + nonce + horodatage).
    Le client vérifie la signature avec la clé publique du service afin de
    détecter toute altération du verdict (ex. MITM transformant 'dangerous'
    en 'safe').
    """

    verdict: Verdict
    score: int = Field(..., ge=0, le=100)
    # Rapport sérialisé EXACTEMENT tel qu'il a été haché dans report_sha256. Le
    # client hache ces octets et les compare à report_sha256 (lui-même signé) :
    # la capture, la destination finale et les raisons sont ainsi RÉELLEMENT
    # authentifiées (parité garantie, sans re-sérialisation côté client).
    report_canonical: str = Field(
        default="", description="JSON canonique exact du rapport (préimage de report_sha256)."
    )
    client_nonce: str
    issued_at: str = Field(..., description="Horodatage ISO-8601 UTC.")
    expires_at: str = Field(default="", description="Expiration du verdict (anti-rejeu).")
    report_sha256: str = Field(default="", description="SHA-256 du rapport, lié à la signature.")
    canonical_payload: str = Field(
        ..., description="Chaîne canonique exacte qui a été signée."
    )
    signature_ed25519_b64: str = ""
    signature_mldsa65_b64: str = ""
    public_key_ed25519_b64: str = ""
    key_id: str = ""


class HealthResponse(BaseModel):
    status: str = "ok"
    service: str
    environment: str
    dynamic_sandbox: bool
    pq_signature: bool


class PublicKeyResponse(BaseModel):
    key_id: str
    public_key_ed25519_b64: str
    public_key_mldsa65_b64: str = ""
    pq_enabled: bool


# --------------------------------------------------------------------------- #
#  Palier de réputation k-anonyme (vie privée)
# --------------------------------------------------------------------------- #
class ReputationRequest(BaseModel):
    """Requête de réputation par préfixes de hash.

    Le client n'envoie JAMAIS l'URL : uniquement des préfixes de 4 octets
    (hex) issus des expressions hôte/chemin. À acheminer via un relais OHTTP
    pour masquer également l'adresse IP.
    """

    prefixes: List[Annotated[str, Field(pattern=r"^[0-9a-fA-F]{8}$")]] = Field(
        ..., min_length=1, max_length=64,
        description="Préfixes de hash (hex, 8 caractères = 4 octets).",
    )


class ReputationMatch(BaseModel):
    full_hash_hex: str
    categories: List[str] = Field(default_factory=list)
    source: str = ""


class ReputationResponse(BaseModel):
    """Réponse : hashs complets malveillants partageant l'un des préfixes.

    Le client compare EN LOCAL ces hashs complets à ceux de son URL pour
    statuer, sans que le serveur ne connaisse l'URL exacte.
    """

    matches: List[ReputationMatch] = Field(default_factory=list)
    store_size: int = 0


# --------------------------------------------------------------------------- #
#  Racine de confiance post-quantique
# --------------------------------------------------------------------------- #
class KeyManifestResponse(BaseModel):
    """Manifeste de clés signé SLH-DSA. Le client n'épingle que la racine."""

    version: int
    key_id: str
    ed25519_pub_b64: str
    mldsa65_pub_b64: str
    mlkem768_pub_b64: str
    issued_at: str
    not_after: str
    root_alg: str
    canonical: str
    sig_slhdsa_b64: str
    # Racine SLH-DSA (servie ici pour bootstrap ; à épingler côté client).
    root_pub_b64: str


class GatewayKeyResponse(BaseModel):
    """Clés publiques de la passerelle pour l'enveloppe hybride ML-KEM + X25519."""

    mlkem768_pub: str
    x25519_pub: str
    alg: str


# --------------------------------------------------------------------------- #
#  Facturation / abonnement Pro
# --------------------------------------------------------------------------- #
class BillingVerifyRequest(BaseModel):
    """Demande de vérification d'achat : jeton d'achat Play (opaque)."""

    purchase_token: str = Field(..., min_length=8, max_length=4096)


class BillingVerifyResponse(BaseModel):
    """Statut Pro + entitlement signé (mis en cache et présenté par le client)."""

    pro: bool
    entitlement: Optional[str] = None
    plan: Optional[str] = None
    expiry: Optional[str] = None
