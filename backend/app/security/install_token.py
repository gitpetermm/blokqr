"""
Provisionnement d'une installation BlokQR.
Au premier lancement, l'app appelle POST /v1/install et reÃ§oit :
  - install_id      : UUIDv4 (identifiant stable de l'installation, non-PII)
  - hmac_secret_b64 : 32 octets alÃ©atoires, base64. Sert Ã  signer chaque
                      requÃªte /v1/analyze* (HMAC-SHA256, dÃ©fense en profondeur).
ModÃ¨le de confiance :
  - Provisionnement Trust-On-First-Use : la transmission initiale est protÃ©gÃ©e
    UNIQUEMENT par TLS (+ enveloppe PQ pour la requÃªte si activÃ©e). Pas plus
    pas moins que pour un token OAuth classique.
  - Le secret HMAC est stockÃ© cÃ´tÃ© serveur dans Redis (clÃ© devkey:{install_id})
    SANS TTL : un client qui rÃ©installe obtient un nouveau install_id (et
    donc un quota qui repart Ã  zÃ©ro -- c'est le comportement attendu).
  - Aucun lien avec une identitÃ© utilisateur : install_id ne permet pas de
    remonter Ã  une personne (privacy-first).
Le verdict Ed25519 + ML-DSA-65 reste la garantie d'authenticitÃ© de l'ANALYSE.
HMAC ajoute une couche d'authentification de l'ORIGINE (anti-rejeu, anti-MitM
local en cas de TLS compromis), conforme Ã  la philosophie dÃ©fense-en-profondeur.
"""
from __future__ import annotations
import base64
import secrets
import uuid
import redis.asyncio as redis
import logging
_redis = redis.from_url("redis://redis:6379/0", decode_responses=True)

 logger = logging.getLogger(__name__)

async def _dual_write_pg(coro_factory, label):
    try:
        from app.config import get_settings
        if not get_settings().enable_pg_dual_write:
            return
        await coro_factory()
    except Exception:
        logger.warning("pg_dual_write_failed op=%s", label, exc_info=True)

# PrÃ©fixes de clÃ©s Redis (un seul endroit pour les changer si refacto).
DEVKEY_PREFIX = "devkey:"
PRO_PREFIX = "pro:"
# Taille du secret HMAC : 32 octets = 256 bits, taille de sortie de HMAC-SHA256.
HMAC_SECRET_BYTES = 32
async def issue_install() -> tuple[str, str]:
    """GÃ©nÃ¨re une nouvelle installation et retourne (install_id, hmac_secret_b64).
    install_id   : UUIDv4 stable. L'app le stocke en EncryptedSharedPreferences.
    hmac_secret  : 32 octets alÃ©atoires (secrets.token_bytes), base64 standard.
                   L'app le stocke en EncryptedSharedPreferences.
    La paire est aussi stockÃ©e cÃ´tÃ© serveur : Redis devkey:{install_id} -> secret.
    Pas de TTL : la clÃ© reste valide tant que l'app vit. En cas d'Ã©viction LRU
    (Redis sous pression mÃ©moire), l'app dÃ©tecte un 401 Â« device inconnu Â» et
    refait POST /v1/install.
    """
    install_id = str(uuid.uuid4())
    secret_bytes = secrets.token_bytes(HMAC_SECRET_BYTES)
    secret_b64 = base64.b64encode(secret_bytes).decode("ascii")
    # On stocke le secret en base64 (cohÃ©rent avec l'API client), pas en binaire :
    # facilite l'inspection en debug et reste 100% portable.
    await _redis.set(DEVKEY_PREFIX + install_id, secret_b64)
    from app.db import repo
    await _dual_write_pg(lambda: repo.db_create_install(install_id, secret_b64), "issue_install")
    return install_id, secret_b64
async def get_secret(install_id: str) -> bytes | None:
    """RÃ©cupÃ¨re le secret HMAC d'une installation (octets bruts), ou None."""
    if not install_id:
        return None
    secret_b64 = await _redis.get(DEVKEY_PREFIX + install_id)
    if not secret_b64:
        return None
    try:
        return base64.b64decode(secret_b64)
    except Exception:  # noqa: BLE001
        return None
async def install_exists(install_id: str) -> bool:
    """Indique si une installation est connue du serveur."""
    if not install_id:
        return False
    return bool(await _redis.exists(DEVKEY_PREFIX + install_id))
async def mark_pro(install_id: str, ttl_seconds: int) -> None:
    """
    Marque une installation comme Pro pour TTL secondes (dÃ©crÃ©ment automatique
    de Redis). AppelÃ© par /v1/billing/verify aprÃ¨s vÃ©rification Google Play
    rÃ©ussie, et par le rÃ©cepteur RTDN sur renouvellement. Ã€ la fin du TTL, la
    clÃ© disparaÃ®t -> l'installation redevient Free.
    """
    if not install_id or ttl_seconds <= 0:
        return
    await _redis.set(PRO_PREFIX + install_id, "1", ex=int(ttl_seconds))
    # CohÃ©rence mÃ©tier : un Pro actif ne peut pas Ãªtre simultanÃ©ment rÃ©voquÃ©.
    # On efface toujours la marque de rÃ©vocation RTDN (remboursement / hold /
    # expiration) lorsqu'on (re)marque Pro, sinon un rÃ©abonnÃ© resterait bloquÃ©
    # sur /v1/analyze/deep (deep_revoked). Corrige les 3 chemins de mark_pro.
    await _redis.delete("revoked:" + install_id)
async def revoke_pro(install_id: str) -> None:
    """
    Retire IMMÃ‰DIATEMENT la marque Pro (inverse de mark_pro). AppelÃ© par le
    rÃ©cepteur RTDN lorsqu'un abonnement n'est plus actif cÃ´tÃ© Google Play :
    remboursement / chargeback (voidedPurchase), account hold, expiration
    anticipÃ©e. Le quota et /v1/analyze/deep (qui lisent is_pro) repassent alors
    Free sans attendre l'expiration du TTL.
    Idempotent : supprimer une clÃ© absente est sans effet.
    """
    if not install_id:
        return
    await _redis.delete(PRO_PREFIX + install_id)
async def is_pro(install_id: str) -> bool:
    """Vrai si l'installation a un entitlement Pro actif cÃ´tÃ© serveur."""
    if not install_id:
        return False
    return bool(await _redis.exists(PRO_PREFIX + install_id))
