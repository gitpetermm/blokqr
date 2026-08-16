"""
Provisionnement d'une installation BlokQR.
Au premier lancement, l'app appelle POST /v1/install et reçoit :
  - install_id      : UUIDv4 (identifiant stable de l'installation, non-PII)
  - hmac_secret_b64 : 32 octets aléatoires, base64. Sert à signer chaque
                      requête /v1/analyze* (HMAC-SHA256, défense en profondeur).
Modèle de confiance :
  - Provisionnement Trust-On-First-Use : la transmission initiale est protégée
    UNIQUEMENT par TLS (+ enveloppe PQ pour la requête si activée). Pas plus
    pas moins que pour un token OAuth classique.
  - Le secret HMAC est stocké côté serveur dans Redis (clé devkey:{install_id})
    SANS TTL : un client qui réinstalle obtient un nouveau install_id (et
    donc un quota qui repart à zéro -- c'est le comportement attendu).
  - Aucun lien avec une identité utilisateur : install_id ne permet pas de
    remonter à une personne (privacy-first).
Le verdict Ed25519 + ML-DSA-65 reste la garantie d'authenticité de l'ANALYSE.
HMAC ajoute une couche d'authentification de l'ORIGINE (anti-rejeu, anti-MitM
local en cas de TLS compromis), conforme à la philosophie défense-en-profondeur.
"""
from __future__ import annotations
import base64
import secrets
import uuid
import redis.asyncio as redis
_redis = redis.from_url("redis://redis:6379/0", decode_responses=True)
# Préfixes de clés Redis (un seul endroit pour les changer si refacto).
DEVKEY_PREFIX = "devkey:"
PRO_PREFIX = "pro:"
# Taille du secret HMAC : 32 octets = 256 bits, taille de sortie de HMAC-SHA256.
HMAC_SECRET_BYTES = 32
async def issue_install() -> tuple[str, str]:
    """Génère une nouvelle installation et retourne (install_id, hmac_secret_b64).
    install_id   : UUIDv4 stable. L'app le stocke en EncryptedSharedPreferences.
    hmac_secret  : 32 octets aléatoires (secrets.token_bytes), base64 standard.
                   L'app le stocke en EncryptedSharedPreferences.
    La paire est aussi stockée côté serveur : Redis devkey:{install_id} -> secret.
    Pas de TTL : la clé reste valide tant que l'app vit. En cas d'éviction LRU
    (Redis sous pression mémoire), l'app détecte un 401 « device inconnu » et
    refait POST /v1/install.
    """
    install_id = str(uuid.uuid4())
    secret_bytes = secrets.token_bytes(HMAC_SECRET_BYTES)
    secret_b64 = base64.b64encode(secret_bytes).decode("ascii")
    # On stocke le secret en base64 (cohérent avec l'API client), pas en binaire :
    # facilite l'inspection en debug et reste 100% portable.
    await _redis.set(DEVKEY_PREFIX + install_id, secret_b64)
    return install_id, secret_b64
async def get_secret(install_id: str) -> bytes | None:
    """Récupère le secret HMAC d'une installation (octets bruts), ou None."""
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
    Marque une installation comme Pro pour TTL secondes (décrément automatique
    de Redis). Appelé par /v1/billing/verify après vérification Google Play
    réussie, et par le récepteur RTDN sur renouvellement. À la fin du TTL, la
    clé disparaît -> l'installation redevient Free.
    """
    if not install_id or ttl_seconds <= 0:
        return
    await _redis.set(PRO_PREFIX + install_id, "1", ex=int(ttl_seconds))
    # Cohérence métier : un Pro actif ne peut pas être simultanément révoqué.
    # On efface toujours la marque de révocation RTDN (remboursement / hold /
    # expiration) lorsqu'on (re)marque Pro, sinon un réabonné resterait bloqué
    # sur /v1/analyze/deep (deep_revoked). Corrige les 3 chemins de mark_pro.
    await _redis.delete("revoked:" + install_id)
async def revoke_pro(install_id: str) -> None:
    """
    Retire IMMÉDIATEMENT la marque Pro (inverse de mark_pro). Appelé par le
    récepteur RTDN lorsqu'un abonnement n'est plus actif côté Google Play :
    remboursement / chargeback (voidedPurchase), account hold, expiration
    anticipée. Le quota et /v1/analyze/deep (qui lisent is_pro) repassent alors
    Free sans attendre l'expiration du TTL.
    Idempotent : supprimer une clé absente est sans effet.
    """
    if not install_id:
        return
    await _redis.delete(PRO_PREFIX + install_id)
async def is_pro(install_id: str) -> bool:
    """Vrai si l'installation a un entitlement Pro actif côté serveur."""
    if not install_id:
        return False
    return bool(await _redis.exists(PRO_PREFIX + install_id))
