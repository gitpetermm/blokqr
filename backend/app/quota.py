"""
Quota quotidien par installation (compteur Redis).

Principe :
  - Une clé Redis par installation et par jour : quota:{install_id}:{YYYYMMDD}
    valeur = nb de scans consommés. Expiration calée sur fin-de-jour UTC + 2h
    de marge -> jamais d'orphelins (Redis purge tout seul après 26h).
  - Free = 7 scans/jour, Pro = 300/jour.
  - Pro détecté via Redis pro:{install_id} (mis à jour par /v1/billing/verify).
  - INCR atomique AVANT l'analyse : pas de course (un attaquant qui ouvre N
    requêtes parallèles ne dépasse pas le quota).
  - DECR si l'analyse retourne UNKNOWN par timeout (scan « non rendu »).

Reset :
  - UTC côté backend (équitable, simple). La clé porte la date du jour UTC, donc
    à minuit UTC les requêtes basculent sur une nouvelle clé (compteur à 0).
  - L'affichage de l'heure locale du reset est à la charge du client Android.

Sémantique de consume :
  - L'appel `try_consume` retourne un QuotaState avec `consumed: bool`.
  - consumed=True  : le scan a été comptabilisé, used inclut le scan en cours.
                     -> on continue l'analyse, on renvoie 200 + headers X-Quota-*.
  - consumed=False : le quota était déjà atteint avant ce scan, INCR a été
                     annulé par DECR, le compteur reste au plafond.
                     -> 429 + verdict signé d'attestation.
  La propriété `exhausted` (used >= limit) reste utile en lecture pure (peek)
  pour signaler que le PROCHAIN scan sera rejeté.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import redis.asyncio as redis

from app.config import Settings
from app.security.install_token import is_pro

_redis = redis.from_url("redis://redis:6379/0", decode_responses=True)

QUOTA_PREFIX = "quota:"


@dataclass(frozen=True)
class QuotaState:
    """État du quota après une opération (lecture ou tentative de consommation)."""
    install_id: str
    is_pro: bool
    limit: int
    used: int
    reset_at_iso: str           # ISO 8601 UTC (le client convertit en local)
    reset_in_seconds: int       # confort pour le client
    consumed: bool = True       # True si try_consume a réussi (None pour peek)

    @property
    def remaining(self) -> int:
        return max(0, self.limit - self.used)

    @property
    def exhausted(self) -> bool:
        """Le PROCHAIN scan sera-t-il rejeté ? (utile en lecture, peek)."""
        return self.used >= self.limit

    def to_headers(self) -> dict[str, str]:
        """Headers HTTP standards (X-Quota-* + Retry-After si on a refusé)."""
        h = {
            "X-Quota-Used": str(self.used),
            "X-Quota-Limit": str(self.limit),
            "X-Quota-Remaining": str(self.remaining),
            "X-Quota-Reset": self.reset_at_iso,
        }
        # Retry-After uniquement quand on REFUSE (= consumed False).
        if not self.consumed:
            h["Retry-After"] = str(self.reset_in_seconds)
        return h

    def to_json(self) -> dict:
        """Représentation JSON pour /v1/quota et le corps des 429."""
        return {
            "is_pro": self.is_pro,
            "limit": self.limit,
            "used": self.used,
            "remaining": self.remaining,
            "reset_at": self.reset_at_iso,
            "reset_in_seconds": self.reset_in_seconds,
        }


def _today_utc_key(install_id: str) -> str:
    """Clé Redis du compteur du jour, calée sur l'UTC."""
    d = datetime.now(timezone.utc).strftime("%Y%m%d")
    return f"{QUOTA_PREFIX}{install_id}:{d}"


def _seconds_until_utc_midnight() -> int:
    """Secondes jusqu'au prochain reset UTC (minuit)."""
    now = datetime.now(timezone.utc)
    nxt = (now + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    return max(1, int((nxt - now).total_seconds()))


def _next_utc_midnight_iso() -> str:
    """ISO 8601 (UTC) du prochain minuit. Le client convertit en heure locale."""
    now = datetime.now(timezone.utc)
    nxt = (now + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    return nxt.isoformat().replace("+00:00", "Z")


async def _limit_for(install_id: str, settings: Settings) -> tuple[int, bool]:
    """Retourne (limite_quotidienne, est_pro)."""
    pro = await is_pro(install_id)
    if pro:
        return int(settings.pro_daily_quota), True
    return int(settings.free_daily_quota), False


async def peek(install_id: str, settings: Settings) -> QuotaState:
    """Lit l'état du quota sans le modifier (utilisé par GET /v1/quota)."""
    limit, pro = await _limit_for(install_id, settings)
    used_raw = await _redis.get(_today_utc_key(install_id))
    used = int(used_raw) if used_raw else 0
    return QuotaState(
        install_id=install_id,
        is_pro=pro,
        limit=limit,
        used=used,
        reset_at_iso=_next_utc_midnight_iso(),
        reset_in_seconds=_seconds_until_utc_midnight(),
        consumed=True,  # peek ne consomme pas, mais ne refuse rien non plus.
    )


async def try_consume(install_id: str, settings: Settings) -> QuotaState:
    """
    Tente de consommer 1 unité du quota.

    Stratégie atomique :
      1. INCR (atomique côté Redis).
      2. Si c'est le premier INCR du jour, on pose un TTL de fin-de-jour + 2h
         de marge -> le compteur disparaît tout seul.
      3. Si le résultat dépasse la limite (new_used > limit), on DECR pour
         laisser le compteur au plafond exact et on retourne consumed=False.
      4. Sinon, consumed=True : le scan EST autorisé (y compris le dernier
         tout pile -- ex: limit=7, new_used=7, consumed=True).

    L'appelant DOIT vérifier `state.consumed`. Si False -> renvoyer 429.
    """
    limit, pro = await _limit_for(install_id, settings)
    key = _today_utc_key(install_id)

    # 1. INCR atomique.
    new_used = await _redis.incr(key)

    # 2. Premier INCR du jour -> on pose le TTL (INCR ne touche pas au TTL).
    if new_used == 1:
        await _redis.expire(key, _seconds_until_utc_midnight() + 7200)

    # 3. Dépassement strict -> on annule et on refuse.
    if new_used > limit:
        await _redis.decr(key)
        return QuotaState(
            install_id=install_id, is_pro=pro,
            limit=limit, used=limit,       # affichage : plafond
            reset_at_iso=_next_utc_midnight_iso(),
            reset_in_seconds=_seconds_until_utc_midnight(),
            consumed=False,                # REFUSÉ
        )

    # 4. Scan autorisé. used inclut le scan en cours.
    return QuotaState(
        install_id=install_id, is_pro=pro,
        limit=limit, used=new_used,
        reset_at_iso=_next_utc_midnight_iso(),
        reset_in_seconds=_seconds_until_utc_midnight(),
        consumed=True,
    )


async def refund(install_id: str) -> None:
    """
    Rembourse 1 unité (DECR borné à 0) : à appeler quand une analyse a été
    consommée par try_consume mais n'a finalement PAS rendu de verdict utile
    (timeout global, erreur 500, etc.). Idempotent au sens « jamais < 0 ».

    Implémenté en pipeline Lua-équivalent côté Python pour éviter
    une race : on lit, et on décrémente seulement si > 0.
    """
    if not install_id:
        return
    key = _today_utc_key(install_id)
    async with _redis.pipeline(transaction=True) as pipe:
        await pipe.get(key)
        results = await pipe.execute()
    cur = results[0]
    if cur and int(cur) > 0:
        await _redis.decr(key)


# --------------------------------------------------------------------------- #
#  M4_DEEP_FREE : apercu approfondi OFFERT (1/jour) aux utilisateurs gratuits.
#  Compteur Redis SEPARE du quota standard (cle deepfree:), meme mecanique :
#  INCR atomique, TTL minuit UTC + 2h, refund borne a 0. La limite (1/jour) est
#  lue depuis settings.free_deep_daily_quota.
# --------------------------------------------------------------------------- #
DEEPFREE_PREFIX = "deepfree:"


def _deepfree_key(install_id: str) -> str:
    d = datetime.now(timezone.utc).strftime("%Y%m%d")
    return f"{DEEPFREE_PREFIX}{install_id}:{d}"


async def try_consume_deep_free(install_id: str, settings: Settings) -> bool:
    """Tente de consommer 1 apercu approfondi offert du jour.

    Retourne True si accorde (et decremente le grant), False si epuise ou
    desactive (free_deep_daily_quota <= 0). Meme strategie atomique que
    try_consume : INCR puis DECR si depassement. N'accorde JAMAIS a un Pro
    (l'appelant traite le cas Pro avant d'arriver ici).
    """
    limit = int(getattr(settings, "free_deep_daily_quota", 1))
    if limit <= 0:
        return False
    key = _deepfree_key(install_id)
    new_used = await _redis.incr(key)
    if new_used == 1:
        await _redis.expire(key, _seconds_until_utc_midnight() + 7200)
    if new_used > limit:
        await _redis.decr(key)
        return False
    return True


async def refund_deep_free(install_id: str) -> None:
    """Rembourse l'apercu offert (DECR borne a 0) si l'analyse profonde n'a
    pas ete rendue (timeout / erreur) : l'utilisateur ne perd pas son offre."""
    if not install_id:
        return
    key = _deepfree_key(install_id)
    async with _redis.pipeline(transaction=True) as pipe:
        await pipe.get(key)
        results = await pipe.execute()
    cur = results[0]
    if cur and int(cur) > 0:
        await _redis.decr(key)


async def peek_deep_free(install_id: str, settings: Settings) -> bool:
    """Lit sans consommer : True si un apercu offert reste disponible aujourd'hui."""
    limit = int(getattr(settings, "free_deep_daily_quota", 1))
    if limit <= 0:
        return False
    used_raw = await _redis.get(_deepfree_key(install_id))
    used = int(used_raw) if used_raw else 0
    return used < limit
