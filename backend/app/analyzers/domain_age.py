"""
Âge du domaine — signal de fraîcheur RÉEL via RDAP.

Les domaines de phishing sont massivement récents (souvent < 30 jours). L'âge
réel du domaine (sa date d'ENREGISTREMENT obtenue par RDAP) est donc un signal
peu coûteux et à forte valeur.

Conception anti-faux-positifs (essentielle — voir la question posée) :
  - signal de FAIBLE poids, GRADUÉ par tranche d'âge : il ne déclenche JAMAIS
    un verdict à lui seul. Un domaine jeune mais propre reste SAFE ; l'âge ne
    fait que renforcer la prudence quand d'AUTRES indices existent déjà.
  - fail-open : si l'âge est inconnu (RDAP indisponible, pas de date), AUCUN
    signal n'est émis. L'incertitude n'est jamais pénalisée.
  - borné par un court délai réseau + mis en cache : le chemin rapide reste
    rapide (la résolution s'exécute en parallèle de la threat intel).
  - les marques connues sont déjà court-circuitées en amont (analyze_domain),
    donc jamais soumises à ce test.
"""
from __future__ import annotations

import time
from datetime import datetime, timezone
from typing import Dict, Optional, Tuple

import httpx

from app.config import Settings
from app.schemas import Severity, Signal

_SOURCE = "domain_age"

# (âge max en jours, poids, libellé) — gradué, et TOUJOURS en faible poids pour
# ne jamais franchir seul le seuil « suspect » (35). Au-delà de 90 j : rien.
_TIERS: Tuple[Tuple[int, int, str], ...] = (
    (7, 12, "moins de 7 jours"),
    (30, 8, "moins de 30 jours"),
    (90, 4, "moins de 90 jours"),
)

# Cache mémoire (l'âge varie lentement) : registrable -> (age_jours|None, expiry).
_cache: Dict[str, Tuple[Optional[int], float]] = {}


def _registration_age_days(payload: dict) -> Optional[int]:
    """Extrait l'âge (jours) depuis la date d'enregistrement RDAP, ou None."""
    events = payload.get("events") or []
    for ev in events:
        if ev.get("eventAction") == "registration":
            raw = ev.get("eventDate")
            if not raw:
                return None
            try:
                dt = datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
            except ValueError:
                return None
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return max(0, (datetime.now(timezone.utc) - dt).days)
    return None


async def _lookup_age_days(registrable: str, settings: Settings) -> Optional[int]:
    """Interroge RDAP pour l'âge du domaine (jours). None si inconnu (fail-open)."""
    now = time.monotonic()
    cached = _cache.get(registrable)
    if cached is not None and cached[1] > now:
        return cached[0]

    age: Optional[int] = None
    url = f"{settings.rdap_base_url.rstrip('/')}/{registrable}"
    try:
        async with httpx.AsyncClient(
            timeout=settings.domain_age_timeout_seconds,
            follow_redirects=True,  # rdap.org redirige vers le serveur autoritatif
        ) as client:
            resp = await client.get(url, headers={"Accept": "application/rdap+json"})
            if resp.status_code == 200:
                age = _registration_age_days(resp.json())
    except Exception:
        age = None  # fail-open : l'incertitude ne pénalise jamais.

    _cache[registrable] = (age, now + settings.domain_age_cache_ttl_seconds)
    return age


async def domain_age_signal(registrable: str, settings: Settings) -> Optional[Signal]:
    """Signal d'âge gradué et fail-open. None si vieux / inconnu / désactivé."""
    if not getattr(settings, "enable_domain_age", False) or not registrable:
        return None
    age = await _lookup_age_days(registrable, settings)
    if age is None:
        return None
    for max_days, weight, label in _TIERS:
        if age <= max_days:
            return Signal(
                code="young_domain",
                title=f"Domaine récent ({label})",
                detail=(
                    f"Le domaine « {registrable} » a été enregistré il y a "
                    f"{age} jour(s). La jeunesse n'est pas une preuve de malveillance, "
                    "mais les domaines de phishing sont très majoritairement récents : "
                    "prudence accrue lorsqu'elle s'ajoute à d'autres indices."
                ),
                severity=Severity.LOW, weight=weight, source=_SOURCE,
            )
    return None  # > 90 jours : aucun signal.
