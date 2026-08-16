"""
Cache mémoire (TTL + LRU borné) des résultats d'analyse.

But : éviter de relancer tout le pipeline (résolution + rendu headless + threat
intelligence) lorsqu'un même contenu est rescanné — ce qui réduit la latence ET
divise le coût d'un éventuel abus de l'endpoint.

Garanties de sécurité :
  - On ne met en cache QUE des analyses COMPLÈTES (verdict != unknown). Un verdict
    prudent/incomplet (timeout, page de transit non résolue, hold de vie privée)
    ne doit jamais être figé : le scan suivant doit pouvoir retenter.
  - On ne stocke QUE le triplet (verdict, score, rapport). La SIGNATURE n'est
    jamais mise en cache : elle est systématiquement recalculée par requête, liée
    au nonce du client et à la fraîcheur (issued_at / expires_at). Un hit de cache
    reste donc lié au client qui le reçoit (pas de rejeu inter-clients).
  - Le nonce client n'entre JAMAIS dans la clé de cache.
  - Borne mémoire stricte (éviction LRU) : pas de fuite sous charge.
"""
from __future__ import annotations

import time
from collections import OrderedDict
from dataclasses import dataclass
from typing import Optional, Tuple

from app.config import Settings
from app.schemas import AnalysisReport, AnalyzeRequest, Verdict

# Séparateur de champs improbable dans une charge utile (unit separator US).
_SEP = "\x1f"


@dataclass
class CachedAnalysis:
    """Instantané immuable d'une analyse complète (sans signature)."""
    verdict: Verdict
    score: int
    report: AnalysisReport


class AnalysisCache:
    """Cache à expiration (TTL) et capacité bornée (LRU)."""

    def __init__(self, ttl_seconds: float, max_entries: int) -> None:
        self.ttl = float(ttl_seconds)
        self.max = int(max_entries)
        self._store: "OrderedDict[str, Tuple[float, CachedAnalysis]]" = OrderedDict()

    def get(self, key: str) -> Optional[CachedAnalysis]:
        item = self._store.get(key)
        if item is None:
            return None
        expiry, cached = item
        if time.monotonic() >= expiry:
            self._store.pop(key, None)  # purge paresseuse de l'entrée expirée
            return None
        self._store.move_to_end(key)    # marque comme récemment utilisée
        return cached

    def set(self, key: str, cached: CachedAnalysis) -> None:
        if self.ttl <= 0 or self.max <= 0:
            return  # cache effectivement désactivé
        self._store[key] = (time.monotonic() + self.ttl, cached)
        self._store.move_to_end(key)
        while len(self._store) > self.max:
            self._store.popitem(last=False)  # éviction de la plus ancienne

    def clear(self) -> None:
        self._store.clear()

    @property
    def size(self) -> int:
        return len(self._store)


_cache: Optional[AnalysisCache] = None


def get_cache(settings: Settings) -> Optional[AnalysisCache]:
    """Singleton paresseux. Renvoie None si le cache est désactivé en config."""
    global _cache
    if not settings.enable_analysis_cache:
        return None
    if _cache is None:
        _cache = AnalysisCache(
            ttl_seconds=settings.analysis_cache_ttl_seconds,
            max_entries=settings.analysis_cache_max_entries,
        )
    return _cache


def cache_key(request: AnalyzeRequest, deep: bool = False) -> str:
    """Clé de cache LOSSLESS : palier + symbologie + charge brute exacte.

    On garde la charge brute (et non une forme normalisée) pour ne jamais
    fusionner deux entrées qui pourraient différer sur un détail influençant le
    verdict (paramètre de requête, fragment, casse du chemin...). Le palier
    (fast/deep) entre dans la clé pour ne jamais resservir un verdict rapide à
    une analyse profonde (ou l'inverse). Le nonce client n'entre pas dans la
    clé : un même QR rescanné par n'importe qui fait mouche.
    """
    sym = (request.symbology or "").lower()
    tier = "deep" if deep else "fast"
    return f"{tier}{_SEP}{sym}{_SEP}{request.raw_payload}"
