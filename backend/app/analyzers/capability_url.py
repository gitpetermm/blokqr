"""
Détection des « capability-URL » (URL porteuses de capacité/jeton).

Faille identifiée : même via OHTTP (IP masquée), envoyer l'URL complète au
palier profond expose l'URL à la passerelle. Or certaines URL SONT un
identifiant : lien de réinitialisation de mot de passe, de désinscription
(contenant l'e-mail), jeton de session, lien magique. Les analyser en profondeur
revient à divulguer un secret.

Ce module détecte ces motifs côté serveur (et la même logique tourne côté
client) afin de NE PAS lancer l'analyse profonde sans consentement explicite, et
de signaler le risque de confidentialité à l'utilisateur.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass
from urllib.parse import urlsplit, parse_qsl

from app.schemas import Severity, Signal

_SOURCE = "capability"

# Mots-clés de chemin/paramètre typiques d'un lien à usage unique.
_SENSITIVE_KEYS = re.compile(
    r"(token|reset|verify|confirm|magic|invite|unsubscribe|activation|"
    r"auth|session|sso|otp|signature|sig|key|secret|access)",
    re.IGNORECASE,
)
_JWT = re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{6,}\b")


def _shannon_entropy(s: str) -> float:
    if not s:
        return 0.0
    counts = {c: s.count(c) for c in set(s)}
    n = len(s)
    return -sum((c / n) * math.log2(c / n) for c in counts.values())


def _looks_random(token: str) -> bool:
    """Segment long et à forte entropie => probable jeton."""
    return len(token) >= 20 and _shannon_entropy(token) >= 3.5


@dataclass
class CapabilityVerdict:
    is_capability: bool
    reason: str = ""


def assess_capability(url: str) -> CapabilityVerdict:
    parts = urlsplit(url)

    if _JWT.search(url):
        return CapabilityVerdict(True, "jeton JWT détecté dans l'URL")

    # Paramètres de requête sensibles ou à forte entropie.
    for key, value in parse_qsl(parts.query):
        if _SENSITIVE_KEYS.search(key):
            return CapabilityVerdict(True, f"paramètre sensible « {key} »")
        if _looks_random(value):
            return CapabilityVerdict(True, f"valeur à forte entropie (« {key} »)")

    # Segments de chemin longs et aléatoires.
    for seg in parts.path.split("/"):
        if _looks_random(seg):
            return CapabilityVerdict(True, "segment de chemin à forte entropie")
    if _SENSITIVE_KEYS.search(parts.path):
        return CapabilityVerdict(True, "chemin sensible (réinitialisation/confirmation)")

    return CapabilityVerdict(False)


def capability_signal(url: str) -> Signal | None:
    """Signal informatif (n'augmente pas le score, protège la vie privée)."""
    v = assess_capability(url)
    if not v.is_capability:
        return None
    return Signal(
        code="capability_url",
        title="Lien personnel à usage unique",
        detail=(
            f"Cette URL semble contenir un jeton personnel ({v.reason}). "
            "L'analyse approfondie est suspendue par défaut pour ne pas exposer "
            "ce secret ; relancez-la explicitement si vous faites confiance à la source."
        ),
        severity=Severity.INFO,
        weight=0,
        source=_SOURCE,
    )
