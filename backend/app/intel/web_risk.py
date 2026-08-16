"""
Client Web Risk API (Google Cloud) pour l'enrichissement d'analyse BlokQR.

Caractéristiques :
- Appels async via httpx (compatible avec le reste du backend FastAPI).
- Cache Redis pour respecter le free tier (100 000 lookups/mois).
- Respect du expireTime renvoyé par Google (TTL adaptatif par URL).
- Fail-safe : si Web Risk est indisponible, on ne bloque PAS l'analyse globale ;
  on retombe sur les autres signaux (URLhaus, OpenPhish local, etc.).
- Aucun secret en dur : la clé vient de settings.web_risk_api_key.

Référence Google : https://cloud.google.com/web-risk/docs/lookup-api
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import time
from dataclasses import dataclass
from typing import Iterable

import httpx
from redis import asyncio as aioredis

logger = logging.getLogger(__name__)


# ---- Constantes ------------------------------------------------------------

WEB_RISK_URL = "https://webrisk.googleapis.com/v1/uris:search"

# Catégories de menaces interrogées (toutes utiles pour BlokQR).
DEFAULT_THREAT_TYPES: tuple[str, ...] = (
    "MALWARE",
    "SOCIAL_ENGINEERING",
    "UNWANTED_SOFTWARE",
    "SOCIAL_ENGINEERING_EXTENDED_COVERAGE",
)

# TTL plancher / plafond du cache (sécurité, même si Google renvoie autre chose).
CACHE_TTL_MIN_SECONDS = 5 * 60          # 5 min plancher pour les non-hits
CACHE_TTL_MAX_SECONDS = 24 * 60 * 60    # 24 h plafond

# Timeout HTTP côté serveur (Web Risk répond généralement <300 ms).
HTTP_TIMEOUT_SECONDS = 3.0


# ---- Modèles ---------------------------------------------------------------

@dataclass(frozen=True)
class WebRiskVerdict:
    """Résultat normalisé d'un lookup Web Risk pour une URL donnée."""
    uri: str
    threat_types: tuple[str, ...]   # vide => non listé par Google
    cached: bool                    # True si servi depuis Redis
    source: str = "google_web_risk"

    @property
    def is_threat(self) -> bool:
        return bool(self.threat_types)


# ---- Client ----------------------------------------------------------------

class WebRiskClient:
    """
    Client async pour /v1/uris:search.

    Usage typique (dans l'orchestration d'analyse) :

        client = WebRiskClient(api_key=settings.web_risk_api_key, redis=redis)
        verdict = await client.lookup("https://example.com/")
        if verdict.is_threat:
            signals.append("google_web_risk:" + ",".join(verdict.threat_types))
    """

    def __init__(
        self,
        api_key: str,
        redis: aioredis.Redis | None = None,
        http_client: httpx.AsyncClient | None = None,
        threat_types: Iterable[str] = DEFAULT_THREAT_TYPES,
        cache_prefix: str = "wr:v1:",
    ) -> None:
        if not api_key:
            raise ValueError("WebRiskClient: api_key is required")
        self._api_key = api_key
        self._redis = redis
        self._owns_http = http_client is None
        self._http = http_client or httpx.AsyncClient(timeout=HTTP_TIMEOUT_SECONDS)
        self._threat_types = tuple(threat_types)
        self._cache_prefix = cache_prefix

    async def aclose(self) -> None:
        if self._owns_http:
            await self._http.aclose()

    # -- API publique ---------------------------------------------------------

    async def lookup(self, uri: str) -> WebRiskVerdict:
        """
        Retourne le verdict Web Risk pour `uri`. Lève PAS d'exception en cas
        de panne réseau : retourne un verdict vide (pas de menace listée).
        Le cache Redis est utilisé si disponible.
        """
        cache_key = self._cache_key(uri)
        cached = await self._cache_get(cache_key)
        if cached is not None:
            return WebRiskVerdict(uri=uri, threat_types=cached, cached=True)

        try:
            threats, ttl = await self._call_api(uri)
        except (httpx.HTTPError, httpx.TimeoutException) as exc:
            logger.warning("WebRisk lookup failed (network): %s", exc)
            return WebRiskVerdict(uri=uri, threat_types=(), cached=False)
        except Exception as exc:  # noqa: BLE001 (failsafe)
            logger.exception("WebRisk lookup failed (unexpected): %s", exc)
            return WebRiskVerdict(uri=uri, threat_types=(), cached=False)

        await self._cache_set(cache_key, threats, ttl)
        return WebRiskVerdict(uri=uri, threat_types=threats, cached=False)

    # -- Internes -------------------------------------------------------------

    async def _call_api(self, uri: str) -> tuple[tuple[str, ...], int]:
        params: list[tuple[str, str]] = [("uri", uri), ("key", self._api_key)]
        for t in self._threat_types:
            params.append(("threatTypes", t))

        resp = await self._http.get(WEB_RISK_URL, params=params)
        if resp.status_code == 200:
            return self._parse_response(resp.json())
        if resp.status_code == 403:
            logger.error("WebRisk 403: API key restricted, billing disabled or IP not allowed")
        elif resp.status_code == 429:
            logger.warning("WebRisk 429: quota exceeded")
        else:
            logger.warning("WebRisk HTTP %s: %s", resp.status_code, resp.text[:200])
        return ((), CACHE_TTL_MIN_SECONDS)

    def _parse_response(self, payload: dict) -> tuple[tuple[str, ...], int]:
        """
        Réponse type :
          {} (pas de menace) OU
          {"threat": {"threatTypes": ["MALWARE"], "expireTime": "2026-06-15T08:00:00Z"}}
        """
        threat = payload.get("threat") or {}
        types = tuple(threat.get("threatTypes") or ())
        expire = threat.get("expireTime")

        ttl = CACHE_TTL_MIN_SECONDS
        if expire:
            ttl = _iso_to_ttl_seconds(expire)
        # Sans menace, on cache court (les listings peuvent évoluer vite).
        ttl = max(CACHE_TTL_MIN_SECONDS, min(ttl, CACHE_TTL_MAX_SECONDS))
        return types, ttl

    def _cache_key(self, uri: str) -> str:
        # On hash l'URL pour éviter les caractères problématiques et borner la taille.
        digest = hashlib.sha256(uri.encode("utf-8")).hexdigest()[:32]
        return f"{self._cache_prefix}{digest}"

    async def _cache_get(self, key: str) -> tuple[str, ...] | None:
        if self._redis is None:
            return None
        try:
            raw = await self._redis.get(key)
        except Exception as exc:  # noqa: BLE001 (cache failure must not break the call)
            logger.warning("WebRisk redis get failed: %s", exc)
            return None
        if raw is None:
            return None
        # Format compact : "" pour absence, "MALWARE,SOCIAL_ENGINEERING" sinon.
        s = raw.decode("utf-8") if isinstance(raw, (bytes, bytearray)) else raw
        return tuple(x for x in s.split(",") if x)

    async def _cache_set(self, key: str, threats: tuple[str, ...], ttl: int) -> None:
        if self._redis is None:
            return
        try:
            await self._redis.set(key, ",".join(threats), ex=ttl)
        except Exception as exc:  # noqa: BLE001
            logger.warning("WebRisk redis set failed: %s", exc)


def _iso_to_ttl_seconds(expire_iso: str) -> int:
    """
    Convertit un timestamp ISO 8601 (UTC) renvoyé par Google en TTL relatif.
    Tolérant : si le parsing échoue, retourne le plancher.
    """
    try:
        # Format Google: "2026-06-15T08:00:00.000Z" ou "...Z"
        from datetime import datetime, timezone

        s = expire_iso.replace("Z", "+00:00")
        dt = datetime.fromisoformat(s)
        delta = dt.timestamp() - time.time()
        return int(max(CACHE_TTL_MIN_SECONDS, delta))
    except Exception:  # noqa: BLE001
        return CACHE_TTL_MIN_SECONDS
