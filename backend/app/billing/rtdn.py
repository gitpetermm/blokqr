"""
Récepteur RTDN (Real-Time Developer Notifications) Google Play -- BlokQR.

Maintient AUTOMATIQUEMENT la marque Pro serveur (pro:{install_id}) à jour :
  - renouvellement / actif / grâce      -> mark_pro (le vrai payeur ne perd
                                            jamais l'accès) ;
  - remboursement / hold / expiration   -> revoke_pro + drapeau "revoked"
                                            (neutralise aussi un jeton signé
                                            encore en cache côté client).

Réutilise l'existant (aucune nouvelle dépendance) :
  - app.billing.play_verifier.verify_subscription  -> PlayVerification(.entitled,
                                                      .expiry_iso, ...) ; .entitled
                                                      inclut déjà la période de grâce
                                                      et l'annulation jusqu'à expiry ;
  - app.security.install_token.mark_pro / revoke_pro -> source de vérité Pro.

Exports :
  router                                    -> à monter dans main.py
  link_purchase(purchase_token, install_id) -> appeler depuis /v1/billing/verify
  deep_revoked(install_id)                  -> consulter dans /v1/analyze/deep
"""
from __future__ import annotations
import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
from datetime import datetime, timezone

import httpx
import redis.asyncio as redis
from fastapi import APIRouter, HTTPException, Query, Request

from app.config import get_settings
from app.billing.play_verifier import verify_subscription
from app.security.install_token import mark_pro, revoke_pro

logger = logging.getLogger(__name__)

# --- Configuration (env) ---------------------------------------------------- #
REDIS_URL = os.getenv("REDIS_URL", "redis://redis:6379/0")
RTDN_PUSH_SECRET = os.getenv("RTDN_PUSH_SECRET", "")
# Lien purchaseToken -> install : doit couvrir un cycle annuel même si l'app
# n'est pas rouverte (sinon une RTDN de renouvellement ne retrouve pas l'install).
LINK_TTL = int(os.getenv("BILLING_LINK_TTL", "34560000"))  # 400 jours

_redis = redis.from_url(REDIS_URL, decode_responses=True)
router = APIRouter(prefix="/v1/billing", tags=["billing"])


# --- Clés Redis dédiées (namespace RTDN, n'empiète pas sur pro:/devkey:) ----- #
def _pth(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _link_key(pt_hash: str) -> str:
    return f"ptlink:{pt_hash}"


def _revoked_key(install_id: str) -> str:
    return f"revoked:{install_id}"


def _seen_key(message_id: str) -> str:
    return f"rtdn:{message_id}"


# --- API interne (appelée par routes.py) ------------------------------------ #
async def link_purchase(purchase_token: str, install_id: str) -> None:
    """Mémorise purchaseToken -> install_id pour que la RTDN (qui ne connaît que
    le token) retrouve l'installation. À appeler depuis /v1/billing/verify."""
    if purchase_token and install_id:
        await _redis.set(_link_key(_pth(purchase_token)), install_id, ex=LINK_TTL)

        try:
            from app.config import get_settings
            if get_settings().enable_pg_dual_write:
                from app.db import repo
                await repo.db_link_purchase(purchase_token, install_id)
        except Exception:
            logger.warning("pg_dual_write_failed op=link_purchase", exc_info=True)

async def deep_revoked(install_id: str) -> bool:
    """True si une RTDN a révoqué ce Pro (remboursement / hold / expiration).
    À consulter dans /v1/analyze/deep pour neutraliser un jeton signé encore en
    cache côté client (le quota, lui, suit déjà is_pro)."""
    if not install_id:
        return False
    return bool(await _redis.exists(_revoked_key(install_id)))


# --- Helpers internes ------------------------------------------------------- #
def _ttl_from_expiry(expiry_iso: str | None, cap: int) -> int:
    """TTL de la marque Pro = min(plafond, temps jusqu'à expiration Play)."""
    if not expiry_iso:
        return cap
    try:
        exp = datetime.fromisoformat(expiry_iso.replace("Z", "+00:00"))
    except ValueError:
        return cap
    remaining = int((exp - datetime.now(timezone.utc)).total_seconds())
    return 60 if remaining <= 0 else min(cap, remaining)


async def _grant(install_id: str, expiry_iso: str | None, cap: int) -> None:
    await mark_pro(install_id, ttl_seconds=_ttl_from_expiry(expiry_iso, cap))
    await _redis.delete(_revoked_key(install_id))


async def _revoke(install_id: str, ttl: int) -> None:
    await revoke_pro(install_id)
    await _redis.set(_revoked_key(install_id), "1", ex=int(ttl))


# --- Endpoint push Cloud Pub/Sub -------------------------------------------- #
@router.post("/rtdn")
async def rtdn(request: Request, token: str = Query(default="")):
    """Récepteur push RTDN. 200 = message acquitté ; 503 = transitoire (retry)."""
    if not RTDN_PUSH_SECRET or not hmac.compare_digest(token, RTDN_PUSH_SECRET):
        raise HTTPException(status_code=403, detail="forbidden")

    envelope = await request.json()
    msg = (envelope or {}).get("message") or {}
    message_id = msg.get("messageId") or msg.get("message_id")
    data_b64 = msg.get("data")
    if not data_b64:
        return {"ok": True}
    try:
        notif = json.loads(base64.b64decode(data_b64))
    except Exception:
        return {"ok": True}  # illisible -> ack pour ne pas boucler

    if "testNotification" in notif:
        return {"ok": True}
    # Déduplication (Pub/Sub peut livrer plusieurs fois le même message).
    if message_id and not await _redis.set(_seen_key(message_id), "1", nx=True, ex=86400):
        return {"ok": True}

    sub = notif.get("subscriptionNotification")
    voided = notif.get("voidedPurchaseNotification")
    purchase_token = (sub or voided or {}).get("purchaseToken")
    if not purchase_token:
        return {"ok": True}

    install_id = await _redis.get(_link_key(_pth(purchase_token)))
    if not install_id:
        # Achat non (encore) lié à une installation : la marque pro: expirera
        # par TTL, et le prochain /verify (ouverture de l'app) recalera l'état.
        return {"ok": True}

    app_settings = get_settings()
    cap = int(app_settings.entitlement_ttl_seconds)

    # Remboursement / chargeback : révocation immédiate, sans appel Play.
    if voided:
        try:
            await _revoke(install_id, cap)
        except Exception:
            logger.warning("rtdn_revoke_failed", exc_info=True)
            raise HTTPException(status_code=503, detail="transient")
        return {"ok": True}

    # subscriptionNotification : RTDN ne dit que « l'état a changé » -> on
    # interroge Google Play pour la vérité, via la MÊME fonction que /verify.
    try:
        result = await asyncio.to_thread(verify_subscription, purchase_token, app_settings)
    except httpx.HTTPStatusError as exc:
        # 4xx (jeton expiré/invalide/supprimé côté Play) -> révoquer, NE PAS
        # boucler. 5xx -> transitoire -> 503 pour que Pub/Sub réessaie.
        if 400 <= exc.response.status_code < 500:
            await _revoke(install_id, cap)
            return {"ok": True}
        logger.warning("rtdn_play_5xx", exc_info=True)
        raise HTTPException(status_code=503, detail="transient")
    except Exception:
        logger.warning("rtdn_play_unreachable", exc_info=True)
        raise HTTPException(status_code=503, detail="transient")

    try:
        if result.entitled:
            await _grant(install_id, result.expiry_iso, cap)
        else:
            await _revoke(install_id, cap)
    except Exception:
        logger.warning("rtdn_apply_failed", exc_info=True)
        raise HTTPException(status_code=503, detail="transient")
    return {"ok": True}
