"""
Schémas Pydantic du Paquet 2 (quota + install + HMAC).

Volontairement séparés de app/schemas.py pour ne pas toucher au fichier
historique (gros et stable). Importés directement par app/api/routes.py.
"""
from __future__ import annotations

from typing import Optional

from pydantic import BaseModel, Field


# --------------------------------------------------------------------------- #
#  POST /v1/install
# --------------------------------------------------------------------------- #
class InstallRequest(BaseModel):
    """
    Corps de POST /v1/install. Champs optionnels (rétrocompatibilité totale
    avec les anciens clients qui envoient `{}` ou un body vide).

    - `app_version` : informatif, journalisé sans PII.
    - `nonce`       : associé au token Play Integrity (header
                      X-Integrity-Token). Le serveur vérifie que le nonce
                      embarqué dans le JWS Google correspond exactement à
                      cette valeur (anti-rejeu lié à la requête).
                      Format attendu : Base64 URL-safe SANS padding, généré
                      par le client (32 octets aléatoires recommandés).
                      Si absent → le serveur passe en mode « pas de preuve
                      d'intégrité fournie » (comportement configuré par
                      `require_integrity`).
    """
    app_version: Optional[str] = Field(
        default=None,
        max_length=32,
        description="Version d'app (informatif, journalisé sans PII).",
    )
    nonce: Optional[str] = Field(
        default=None,
        max_length=512,
        description=(
            "Nonce Base64 URL-safe utilisé pour générer le token Play "
            "Integrity envoyé en header X-Integrity-Token. Optionnel pour "
            "rétrocompatibilité ; recommandé pour bénéficier de la "
            "protection anti-réinstallation."
        ),
    )


class InstallResponse(BaseModel):
    """Réponse de POST /v1/install."""
    install_id: str = Field(..., description="UUIDv4 stable de l'installation.")
    hmac_secret_b64: str = Field(
        ..., description="Secret HMAC 32 octets, base64. À stocker côté client.",
    )
    hmac_algorithm: str = Field(
        default="HMAC-SHA256",
        description="Algorithme attendu pour signer chaque requête /v1/analyze*.",
    )
    canonical_format: str = Field(
        default="METHOD\\nPATH\\nTIMESTAMP\\nNONCE\\nBODY",
        description=(
            "Format de la chaîne canonique signée. Le client doit la "
            "construire octet pour octet : MÉTHODE en MAJUSCULES, séparateurs "
            "LF, BODY en octets bruts du corps HTTP."
        ),
    )
    free_daily_quota: int = Field(
        ..., description="Limite quotidienne Free, pour information utilisateur.",
    )
    pro_daily_quota: int = Field(
        ..., description="Limite quotidienne Pro, pour information utilisateur.",
    )


# --------------------------------------------------------------------------- #
#  GET /v1/quota
# --------------------------------------------------------------------------- #
class QuotaStatus(BaseModel):
    """État du quota retourné par GET /v1/quota (lecture sans consommer)."""
    is_pro: bool
    limit: int
    used: int
    remaining: int
    reset_at: str = Field(..., description="ISO 8601 UTC du prochain reset.")
    reset_in_seconds: int


# --------------------------------------------------------------------------- #
#  Corps des réponses 429 (quota dépassé) — pas un schéma de retour FastAPI
#  direct, juste documenté ici pour référence.
# --------------------------------------------------------------------------- #
class QuotaExceededBody(BaseModel):
    """
    Corps JSON du 429. Inclut un verdict signé hybride attestant la limite,
    cohérent avec la philosophie « toutes les signatures restent hybrides ».
    """
    detail: str = Field(default="quota_exceeded")
    is_pro: bool
    limit: int
    used: int
    reset_at: str
    reset_in_seconds: int
    recommendation: dict = Field(
        ..., description="Hints pour le client : action primaire/secondaire.",
    )
    signed_attestation: str = Field(
        ..., description=(
            "Verdict signé hybride Ed25519 + ML-DSA-65 attestant que la limite "
            "est légitime (anti-mensonge serveur). Le client peut le vérifier."
        ),
    )
