"""
Agrégateur de Threat Intelligence.

Interroge en parallèle plusieurs sources de réputation et fusionne leurs
verdicts. La diversité des sources réduit l'angle mort des listes noires
individuelles (un domaine fraîchement enregistré peut n'être connu que d'une
seule source). Toutes les clés sont optionnelles : un provider sans clé est
simplement marqué « indisponible » et n'invalide pas l'analyse.

Sources prises en charge :
  - Google Web Risk (Cloud) -- clé requise, free tier 100 000 lookups/mois.
    Remplace Google Safe Browsing v4 (réservé au non commercial).
  - PhishTank (sans clé, rate-limité)
  - URLhaus / abuse.ch (Auth-Key requise)
  - AbuseIPDB -- réputation d'IP (clé requise, 1000-3000 checks/jour gratuits).
    Complémentaire des autres : il évalue l'IP résolue de la cible finale,
    là où les autres évaluent l'URL ou le domaine.
"""
from __future__ import annotations

import asyncio
import socket
from typing import List

import httpx

from app.config import Settings
from app.schemas import ThreatIntelResult


async def _web_risk(
    client: httpx.AsyncClient, url: str, settings: Settings
) -> ThreatIntelResult:
    """
    Google Cloud Web Risk -- Lookup API (uris:search).

    Doc : https://cloud.google.com/web-risk/docs/lookup-api
    Réponse type :
      {} (pas de menace) OU
      {"threat": {"threatTypes": ["MALWARE", ...], "expireTime": "..."}}

    Fail-safe : toute erreur (réseau, quota, 403) renvoie `available=False`
    sans interrompre la chaîne d'analyse globale.
    """
    name = "google_web_risk"
    if not settings.web_risk_api_key:
        return ThreatIntelResult(provider=name, available=False)

    # Catégories interrogées (toutes utiles pour BlokQR).
    threat_types = (
        "MALWARE",
        "SOCIAL_ENGINEERING",
        "UNWANTED_SOFTWARE",
        "SOCIAL_ENGINEERING_EXTENDED_COVERAGE",
    )
    params: list[tuple[str, str]] = [
        ("uri", url),
        ("key", settings.web_risk_api_key),
    ]
    for t in threat_types:
        params.append(("threatTypes", t))

    try:
        resp = await client.get(
            "https://webrisk.googleapis.com/v1/uris:search",
            params=params,
        )
        resp.raise_for_status()
        threat = (resp.json() or {}).get("threat") or {}
        cats = sorted(set(threat.get("threatTypes") or ()))
        listed = bool(cats)
        return ThreatIntelResult(
            provider=name,
            malicious=listed,
            categories=cats,
            detail=(
                f"Listé par Google Web Risk ({', '.join(cats)})"
                if listed else "Aucune correspondance"
            ),
        )
    except Exception as exc:  # noqa: BLE001
        return ThreatIntelResult(provider=name, available=False, detail=str(exc))


async def _phishtank(
    client: httpx.AsyncClient, url: str, settings: Settings
) -> ThreatIntelResult:
    name = "phishtank"
    if not settings.enable_phishtank:
        return ThreatIntelResult(provider=name, available=False)
    try:
        resp = await client.post(
            "https://checkurl.phishtank.com/checkurl/",
            data={"url": url, "format": "json"},
            headers={"User-Agent": "phishtank/blokqr"},
        )
        resp.raise_for_status()
        results = resp.json().get("results", {})
        in_db = bool(results.get("in_database"))
        is_phish = bool(results.get("valid")) and in_db
        return ThreatIntelResult(
            provider=name, malicious=is_phish,
            detail=("Confirmé hameçonnage par PhishTank" if is_phish
                    else "Non répertorié comme hameçonnage"),
        )
    except Exception as exc:  # noqa: BLE001
        return ThreatIntelResult(provider=name, available=False, detail=str(exc))


async def _urlhaus(
    client: httpx.AsyncClient, url: str, settings: Settings
) -> ThreatIntelResult:
    name = "urlhaus"
    if not settings.enable_urlhaus:
        return ThreatIntelResult(provider=name, available=False)
    if not settings.urlhaus_auth_key:
        # URLhaus exige une Auth-Key (abuse.ch) : sans clé, on n'appelle pas
        # (évite un 401 systématique) et on marque la source indisponible.
        return ThreatIntelResult(
            provider=name, available=False,
            detail="Clé abuse.ch (Auth-Key) non configurée.",
        )
    try:
        resp = await client.post(
            "https://urlhaus-api.abuse.ch/v1/url/",
            data={"url": url},
            headers={"Auth-Key": settings.urlhaus_auth_key},
        )
        resp.raise_for_status()
        data = resp.json()
        listed = data.get("query_status") == "ok"
        threat = data.get("threat", "")
        return ThreatIntelResult(
            provider=name, malicious=listed,
            categories=([threat] if threat else []),
            detail=(f"Listé URLhaus ({threat})" if listed else "Non listé"),
        )
    except Exception as exc:  # noqa: BLE001
        return ThreatIntelResult(provider=name, available=False, detail=str(exc))


async def _resolve_host_ip(host: str, timeout: float = 1.5) -> str | None:
    """
    Résout un nom d'hôte vers une IP (A/AAAA), avec un timeout court et sans
    bloquer la boucle. Retourne None en cas d'échec ou de hostname déjà-IP-littérale.
    """
    if not host:
        return None
    # Si c'est déjà une IP (littérale), on la renvoie telle quelle.
    try:
        from ipaddress import ip_address
        ip_address(host.strip("[]"))
        return host.strip("[]")
    except ValueError:
        pass
    try:
        loop = asyncio.get_running_loop()
        info = await asyncio.wait_for(
            loop.getaddrinfo(host, None),
            timeout=timeout,
        )
    except Exception:  # noqa: BLE001
        return None
    for family, _type, _proto, _cname, sockaddr in info:
        if family in (socket.AF_INET, socket.AF_INET6):
            return sockaddr[0]
    return None


async def _abuseipdb(
    client: httpx.AsyncClient, url: str, settings: Settings
) -> ThreatIntelResult:
    """
    AbuseIPDB -- réputation d'IP.

    Évalue l'IP du host de la cible finale. AbuseIPDB renvoie un
    `abuseConfidenceScore` de 0 à 100 (0 = sain, 100 = signalements abusifs
    confirmés). Le code considère malveillant à partir d'un seuil prudent (>=
    50), pour éviter le bruit des CDN partagés (Cloudflare, etc.) qui
    accumulent quelques signalements indépendamment du contenu hébergé.
    """
    name = "abuseipdb"
    if not settings.abuseipdb_api_key:
        return ThreatIntelResult(provider=name, available=False)

    try:
        from urllib.parse import urlsplit
        host = urlsplit(url).hostname or ""
    except Exception:  # noqa: BLE001
        return ThreatIntelResult(provider=name, available=False,
                                 detail="URL invalide")
    if not host:
        return ThreatIntelResult(provider=name, available=False,
                                 detail="Hôte introuvable")

    ip = await _resolve_host_ip(host)
    if ip is None:
        return ThreatIntelResult(provider=name, available=False,
                                 detail="Résolution DNS impossible")

    headers = {
        "Accept": "application/json",
        "Key": settings.abuseipdb_api_key,
    }
    params = {"ipAddress": ip, "maxAgeInDays": "90"}
    try:
        resp = await client.get(
            "https://api.abuseipdb.com/api/v2/check",
            headers=headers, params=params,
        )
        resp.raise_for_status()
        data = (resp.json() or {}).get("data") or {}
        score = int(data.get("abuseConfidenceScore") or 0)
        country = data.get("countryCode") or ""
        usage = data.get("usageType") or ""
        reports = int(data.get("totalReports") or 0)
        is_malicious = score >= 50
        details = f"Score {score}/100 ({reports} signalements, {usage or 'usage inconnu'}{', '+country if country else ''})"
        return ThreatIntelResult(
            provider=name,
            malicious=is_malicious,
            categories=([f"abuseConfidence={score}"] if is_malicious else []),
            detail=details,
            raw_score=round(score / 100.0, 3),
        )
    except Exception as exc:  # noqa: BLE001
        return ThreatIntelResult(provider=name, available=False, detail=str(exc))


def _sanitize_for_ti(url: str) -> str:
    """Retire les éléments porteurs de secrets (identifiants intégrés, fragment)
    avant toute interrogation d'un service tiers : ils n'ont aucune valeur pour la
    réputation et pourraient exposer un jeton (ex. un access_token placé en fragment).
    La query est conservée car elle porte un signal utile à la détection."""
    try:
        from urllib.parse import urlsplit, urlunsplit
        p = urlsplit(url)
        host = p.hostname or ""
        if ":" in host:
            host = f"[{host}]"
        netloc = f"{host}:{p.port}" if p.port else host
        return urlunsplit((p.scheme, netloc, p.path, p.query, ""))
    except Exception:  # noqa: BLE001
        return url


async def gather_threat_intel(url: str, settings: Settings) -> List[ThreatIntelResult]:
    """Interroge toutes les sources en parallèle avec un budget de temps borné."""
    url = _sanitize_for_ti(url)
    async with httpx.AsyncClient(
        timeout=settings.threat_intel_timeout_seconds,
        headers={"User-Agent": "blokqr/1.0"},
    ) as client:
        tasks = [
            _web_risk(client, url, settings),
            _phishtank(client, url, settings),
            _urlhaus(client, url, settings),
            _abuseipdb(client, url, settings),
        ]
        try:
            results = await asyncio.wait_for(
                asyncio.gather(*tasks, return_exceptions=False),
                timeout=settings.threat_intel_timeout_seconds + 2,
            )
        except asyncio.TimeoutError:
            return [ThreatIntelResult(
                provider="threat_intel", available=False,
                detail="Délai global dépassé",
            )]
    return list(results)
