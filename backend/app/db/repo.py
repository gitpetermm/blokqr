"""
Couche d'acces (repository) PostgreSQL -- BlokQR.

Fonctions miroir de ce que font aujourd'hui install_token.py (installs) et
rtdn.py (liens d'achat) avec Redis. Signatures pensees pour un remplacement
progressif :
  Redis create_install  <-> db_create_install
  Redis get_secret      <-> db_get_secret
  Redis install_exists  <-> db_install_exists
  Redis link_purchase   <-> db_link_purchase

IMPORTANT : ce module n'est appele NULLE PART en production pour l'instant.
Il est teste isolement. Le branchement se fera en Phase 2 (double ecriture).
"""
from __future__ import annotations

import hashlib
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.db.engine import get_session
from app.db.models import Install, Subscription


def purchase_token_hash(token: str) -> str:
    """sha256 hex du purchaseToken (identique a rtdn._pth)."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


# --- Installs --------------------------------------------------------------- #
async def db_create_install(install_id: str, hmac_secret_b64: str) -> None:
    """Cree (ou met a jour) une installation. Idempotent (upsert)."""
    async with get_session() as session:
        stmt = pg_insert(Install).values(
            install_id=install_id, hmac_secret=hmac_secret_b64,
        ).on_conflict_do_update(
            index_elements=[Install.install_id],
            set_={"hmac_secret": hmac_secret_b64},
        )
        await session.execute(stmt)


async def db_get_secret(install_id: str) -> str | None:
    """Retourne le secret HMAC (base64) d'une install, ou None si absente."""
    if not install_id:
        return None
    async with get_session() as session:
        row = await session.get(Install, install_id)
        return row.hmac_secret if row else None


async def db_install_exists(install_id: str) -> bool:
    """True si l'installation existe."""
    if not install_id:
        return False
    async with get_session() as session:
        row = await session.get(Install, install_id)
        return row is not None


async def db_touch_install(install_id: str) -> None:
    """Met a jour last_seen_at (usage optionnel, telemetrie legere)."""
    async with get_session() as session:
        row = await session.get(Install, install_id)
        if row:
            row.last_seen_at = datetime.utcnow()


# --- Subscriptions / liens d'achat ------------------------------------------ #
async def db_link_purchase(
    purchase_token: str, install_id: str,
    product_id: str | None = None, state: str | None = None,
    expiry_at: datetime | None = None,
) -> None:
    """Memorise purchaseToken(hache) -> install_id (+ etat). Idempotent (upsert).
    Miroir de rtdn.link_purchase, en stockant le HASH du token (jamais en clair)."""
    if not purchase_token or not install_id:
        return
    pth = purchase_token_hash(purchase_token)
    async with get_session() as session:
        values = {
            "purchase_token_hash": pth, "install_id": install_id,
            "product_id": product_id, "state": state, "expiry_at": expiry_at,
        }
        update_set = {k: v for k, v in values.items()
                      if k != "purchase_token_hash" and v is not None}
        stmt = pg_insert(Subscription).values(**values).on_conflict_do_update(
            index_elements=[Subscription.purchase_token_hash],
            set_=update_set or {"install_id": install_id},
        )
        await session.execute(stmt)


async def db_install_for_token(purchase_token: str) -> str | None:
    """Retrouve l'install_id lie a un purchaseToken. Miroir du GET ptlink:."""
    if not purchase_token:
        return None
    pth = purchase_token_hash(purchase_token)
    async with get_session() as session:
        row = await session.get(Subscription, pth)
        return row.install_id if row else None


# --- Utilitaires de comptage (verification / migration) --------------------- #
async def db_count_installs() -> int:
    async with get_session() as session:
        res = await session.execute(select(Install))
        return len(res.scalars().all())


async def db_count_subscriptions() -> int:
    async with get_session() as session:
        res = await session.execute(select(Subscription))
        return len(res.scalars().all())
