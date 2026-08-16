"""
Authentification HMAC-SHA256 par installation (défense en profondeur).

Couche complémentaire à TLS + verdict signé Ed25519 + ML-DSA-65 :
  - TLS : confidentialité + intégrité du transport.
  - Verdict signé hybride : authenticité de l'ANALYSE (anti-falsification serveur).
  - HMAC : authenticité de l'ORIGINE (anti-rejeu, anti-MitM local).

Contrat HMAC :
  Format de la chaîne signée (séparateurs LF pour éviter toute ambiguïté
  de concaténation à la AWS Signature v4) :

    METHOD\\nPATH\\nTIMESTAMP\\nNONCE\\nBODY_BYTES

  Algorithme : HMAC-SHA256, encodage hex (64 caractères).
  En-têtes attendus côté client :
    X-Install-Id   : UUIDv4 (toujours obligatoire si require_install_id).
    X-Timestamp    : entier UNIX en secondes (UTC).
    X-Nonce        : aléatoire base64url, ≥ 16 octets décodés.
    X-Hmac         : hex 64 caractères.

Politique :
  - Mode PERMISSIF (settings.require_hmac == False) :
       En-têtes absents → on laisse passer, on émet un log d'avertissement.
       En-têtes présents → vérification stricte (toute violation = 401/409).
  - Mode STRICT (settings.require_hmac == True) : tout absent ou invalide = 401.

  X-Install-Id reste obligatoire dans les DEUX modes (quota côté serveur).
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import logging
import time

import redis.asyncio as redis
from fastapi import HTTPException, Request

from app.config import Settings
from app.security.install_token import get_secret, install_exists

logger = logging.getLogger("blokqr.hmac")

_redis = redis.from_url("redis://redis:6379/0", decode_responses=True)

NONCE_PREFIX = "nonce:"
MIN_NONCE_DECODED_BYTES = 16  # Anti-collision (probabilité < 2^-64 sur N requêtes).


def _missing_hmac_headers(request: Request) -> bool:
    """Vrai si aucun des 3 headers HMAC n'est présent (mode permissif possible)."""
    return not (
        request.headers.get("x-timestamp")
        or request.headers.get("x-nonce")
        or request.headers.get("x-hmac")
    )


def _build_canonical(method: str, path: str, ts: str, nonce: str, body: bytes) -> bytes:
    """Chaîne canonique exacte à signer (octets), reproductible par le client.

    Le client doit construire la même chaîne EXACTE : séparateurs LF, body
    EN OCTETS (pas en string Python) pour les requêtes avec corps binaire.
    """
    head = f"{method.upper()}\n{path}\n{ts}\n{nonce}\n".encode("utf-8")
    return head + (body or b"")


async def verify_hmac(
    request: Request, body: bytes, settings: Settings
) -> str:
    """
    Vérifie l'authentification HMAC d'une requête et retourne l'install_id.

    Lève HTTPException(401/409) en cas d'échec strict.
    Retourne l'install_id même en mode permissif (tant que X-Install-Id est là).
    """
    install_id = (request.headers.get("x-install-id") or "").strip()

    # X-Install-Id est OBLIGATOIRE dans les deux modes (quota par installation).
    if not install_id:
        raise HTTPException(
            status_code=401,
            detail="install_id_missing",
            headers={"WWW-Authenticate": "BlokQR-Install"},
        )

    # Existence côté serveur : l'app doit avoir appelé /v1/install au préalable.
    if not await install_exists(install_id):
        logger.warning("install_unknown rejete: install_id=%s path=%s", install_id, request.url.path)
        raise HTTPException(
            status_code=401,
            detail="install_unknown",  # le client doit refaire /v1/install
            headers={"WWW-Authenticate": "BlokQR-Install"},
        )

    ts = request.headers.get("x-timestamp")
    nonce = request.headers.get("x-nonce")
    sig_hex = request.headers.get("x-hmac")

    # Mode PERMISSIF : si aucun en-tête HMAC, on laisse passer avec un log.
    if _missing_hmac_headers(request):
        if settings.require_hmac:
            raise HTTPException(status_code=401, detail="hmac_headers_missing")
        logger.warning(
            "hmac_missing install_id=%s path=%s method=%s -- mode permissif",
            install_id, request.url.path, request.method,
        )
        return install_id

    # Mode strict OU en-têtes partiels en mode permissif -> on vérifie tout.
    if not (ts and nonce and sig_hex):
        raise HTTPException(status_code=400, detail="hmac_headers_incomplete")

    # 1. Timestamp dans la fenêtre (anti-rejeu temporel).
    try:
        ts_int = int(ts)
    except ValueError:
        raise HTTPException(status_code=400, detail="bad_timestamp")
    now = int(time.time())
    window = int(settings.hmac_window_seconds)
    if abs(now - ts_int) > window:
        raise HTTPException(status_code=401, detail="timestamp_out_of_window")

    # 2. Nonce de taille minimale (anti-collision).
    try:
        nonce_bytes = base64.urlsafe_b64decode(nonce + "==")
    except Exception:  # noqa: BLE001
        raise HTTPException(status_code=400, detail="bad_nonce_encoding")
    if len(nonce_bytes) < MIN_NONCE_DECODED_BYTES:
        raise HTTPException(status_code=400, detail="nonce_too_short")

    # 3. Récupération du secret + vérification de la signature.
    secret = await get_secret(install_id)
    if not secret:
        # Race condition rare : install_exists OK mais secret évincé entre-temps.
        raise HTTPException(status_code=401, detail="install_unknown")
    canonical = _build_canonical(
        request.method, request.url.path, ts, nonce, body
    )
    expected_hex = hmac.new(secret, canonical, hashlib.sha256).hexdigest()
    # compare_digest = comparaison à temps constant (anti timing-attack).
    if not hmac.compare_digest(expected_hex, sig_hex.lower()):
        raise HTTPException(status_code=401, detail="hmac_invalid")

    # 4. Unicité du nonce (anti-rejeu strict, fenêtre = 2 × hmac_window).
    nonce_key = f"{NONCE_PREFIX}{install_id}:{nonce}"
    fresh = await _redis.set(nonce_key, "1", nx=True, ex=window * 2 + 5)
    if not fresh:
        raise HTTPException(status_code=409, detail="nonce_replayed")

    return install_id
