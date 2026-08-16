"""
Étage IA (Gemini) — 2e ligne d'analyse, pour les cas AMBIGUS uniquement.

Sollicité par le pipeline seulement quand la threat intelligence ne liste rien
mais qu'un signal reste douteux. C'est l'angle mort de Web Risk : le phishing
nouveau, pas encore listé.

Confidentialité : n'envoie que des MÉTADONNÉES (URL finale, chaîne de
redirection, signaux du sandbox). Jamais le contenu brut de la page ni la capture.

Cache Redis AUTONOME : ce module gère sa propre connexion Redis (même URL que le
reste du projet, redis://redis:6379/0). Le verdict IA est mémorisé (clé = hash de
l'URL finale) pour ne PAS rappeler ni repayer Gemini sur une URL déjà analysée
pendant la fenêtre de cache. Aucune modification du pipeline n'est nécessaire.

Renvoie un Signal pondéré qui s'ajoute au score. Fail-safe : toute erreur (panne
réseau, quota, JSON illisible, Redis indisponible) n'interrompt jamais le scan.
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
from typing import List, Optional

from google import genai
from google.genai import types
from redis import asyncio as aioredis

from app.config import get_settings
from app.schemas import Severity, Signal

logger = logging.getLogger(__name__)

_settings = get_settings()
# Client Gemini créé une seule fois (None si aucune clé -> étage inactif).
_client = genai.Client(api_key=_settings.gemini_api_key) if _settings.gemini_api_key else None

# Connexion Redis autonome, même convention que le reste du projet
# (quota.py, hmac_auth.py, rtdn.py). decode_responses=True -> valeurs en str.
_REDIS_URL = os.getenv("REDIS_URL", "redis://redis:6379/0")
try:
    _redis = aioredis.from_url(_REDIS_URL, decode_responses=True)
except Exception as exc:  # noqa: BLE001 (le cache est optionnel, jamais bloquant)
    logger.warning("Cache IA : connexion Redis indisponible (%s) — cache desactive", exc)
    _redis = None

_SOURCE = "ai_gemini"
_CACHE_PREFIX = "ai:v1:"

_SYSTEM = (
    "Tu es un analyste anti-hameconnage. A partir d'une URL finale, de sa chaine "
    "de redirection et de signaux d'observation (marque detectee, cloaking, mur "
    "anti-robot), evalue le risque d'usurpation ou de phishing, y compris pour "
    "des marques hors d'une liste connue. Indices: domaine imitant une marque "
    "(typosquatting, homoglyphes, marque en sous-domaine), incoherence entre la "
    "marque affichee et le domaine reel, techniques d'evasion. Reponds "
    "STRICTEMENT en JSON, sans texte autour: "
    '{"verdict":"safe|caution|dangerous","brand":"<marque ou null>",'
    '"reason":"<1 phrase>"}.'
)

# Poids ajoutés au score selon le verdict IA. Volontairement modestes : l'IA
# CONTRIBUE au score, elle ne le décide pas seule (seuils dans la config).
_WEIGHTS = {"dangerous": 30, "caution": 14}
_SEVERITIES = {"dangerous": Severity.HIGH, "caution": Severity.MEDIUM}


def _cache_key(final_url: str) -> str:
    digest = hashlib.sha256(final_url.encode("utf-8")).hexdigest()[:32]
    return f"{_CACHE_PREFIX}{digest}"


def _signal_from_data(data: dict) -> Optional[Signal]:
    """Construit un Signal pondéré à partir du dict de verdict IA (None si 'safe')."""
    verdict = str(data.get("verdict", "safe")).lower()
    weight = _WEIGHTS.get(verdict, 0)
    if weight <= 0:
        return None
    brand = data.get("brand") or "?"
    title = (f"IA : risque d'usurpation « {brand} »" if verdict == "dangerous"
             else "IA : indices d'hameconnage")
    return Signal(
        code="ai_impersonation",
        title=title,
        detail=str(data.get("reason", ""))[:300],
        severity=_SEVERITIES.get(verdict, Severity.INFO),
        weight=weight,
        source=_SOURCE,
    )


def _build_prompt(final_url: str, redirect_chain: List[str], host: str,
                  brand_hint: Optional[str], cloaking: bool, gating: bool) -> str:
    chain = " -> ".join(redirect_chain) if redirect_chain else final_url
    return (
        f"URL finale: {final_url}\n"
        f"Domaine enregistrable: {host or 'inconnu'}\n"
        f"Chaine de redirection: {chain}\n"
        f"Marque detectee par le sandbox (possiblement incomplete): {brand_hint or 'aucune'}\n"
        f"Cloaking: {cloaking} | Mur anti-robot: {gating}"
    )


async def _cache_get(key: str):
    """Lecture cache. Renvoie: None (absent), "" (safe memorise), ou dict de verdict.
    Fail-safe : une panne Redis renvoie None (on rappellera Gemini)."""
    if _redis is None:
        return None
    try:
        raw = await _redis.get(key)   # str (decode_responses=True) ou None
    except Exception as exc:  # noqa: BLE001
        logger.warning("Cache IA (get) indisponible: %s", exc)
        return None
    if raw is None:
        return None
    if raw == "":
        return ""            # sentinelle : verdict 'safe' deja memorise
    try:
        return json.loads(raw)
    except Exception:  # noqa: BLE001 (entree corrompue -> on ignore le cache)
        return None


async def _cache_set(key: str, data: Optional[dict]) -> None:
    """Écriture cache (fail-safe). data=None -> memorise "" (safe)."""
    if _redis is None:
        return
    try:
        payload = "" if data is None else json.dumps(data, ensure_ascii=False)
        await _redis.set(key, payload, ex=_settings.ai_cache_ttl_seconds)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Cache IA (set) indisponible: %s", exc)


async def ai_analyze(
    final_url: str,
    redirect_chain: List[str],
    host: str = "",
    brand_hint: Optional[str] = None,
    cloaking: bool = False,
    gating: bool = False,
) -> Optional[Signal]:
    """Renvoie un Signal pondéré, ou None (IA indisponible / verdict 'safe').

    Le cache Redis (interne) évite de rappeler Gemini sur une URL déjà analysée.
    L'appelant DOIT tolérer None : l'IA ne doit jamais casser le flux de scan.
    """
    if _client is None:
        return None

    key = _cache_key(final_url)

    # 1) Cache : hit -> pas d'appel Gemini (donc pas de cout).
    cached = await _cache_get(key)
    if cached == "":
        return None
    if isinstance(cached, dict):
        return _signal_from_data(cached)

    # 2) Appel Gemini.
    try:
        resp = await _client.aio.models.generate_content(
            model=_settings.gemini_model,
            contents=_build_prompt(final_url, redirect_chain, host, brand_hint,
                                   cloaking, gating),
            config=types.GenerateContentConfig(
                system_instruction=_SYSTEM,
                temperature=0,
                max_output_tokens=800,
                response_mime_type="application/json",
                # Pas de "réflexion" : classification courte -> rapide et bon marché.
                # À retirer si le modèle choisi ne le supporte pas.
            ),
        )
        data = json.loads(resp.text)
    except Exception as exc:  # panne, quota, JSON illisible... on n'échoue jamais
        logger.warning("Etage IA Gemini indisponible: %s", exc)
        return None

    # 3) Mémorise le résultat (y compris 'safe' via sentinelle "") puis renvoie.
    signal = _signal_from_data(data)
    await _cache_set(key, data if signal is not None else None)
    return signal
