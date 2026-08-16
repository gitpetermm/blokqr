"""
Intelligence de domaine — détection d'usurpation par le DOMAINE.

Justification : contre les kits AiTM (type Evilginx) qui relaient la vraie page,
l'analyse VISUELLE est inutile (la page est un proxy de l'authentique). Seul le
domaine trahit l'attaque. Ce module :

  - extrait le domaine enregistrable (eTLD+1) via une liste de suffixes publics
    compacte et embarquée (offline) ;
  - détecte les sosies de marques connues (homoglyphes, fautes de frappe,
    combosquatting, punycode/IDN) par distance d'édition ;
  - signale un domaine jamais observé localement (heuristique « fraîcheur »).

La liste de marques et de suffixes est volontairement réduite et extensible.
"""
from __future__ import annotations

import unicodedata
from dataclasses import dataclass
from typing import List, Optional
from urllib.parse import urlsplit

from app.schemas import Severity, Signal

_SOURCE = "domain_intel"

# Suffixes publics multi-niveaux fréquents (extrait ; remplaçable par la PSL complète).
_MULTI_SUFFIXES = {
    "co.uk", "org.uk", "gov.uk", "ac.uk", "com.au", "com.br", "com.cn",
    "co.jp", "co.in", "co.za", "gouv.fr", "com.mx", "com.tr", "co.kr",
}

# Marques sensibles : domaine enregistrable légitime -> libellé.
_KNOWN_BRANDS = {
    "microsoft.com": "Microsoft", "office.com": "Microsoft 365",
    "live.com": "Microsoft", "google.com": "Google", "apple.com": "Apple",
    "icloud.com": "Apple", "paypal.com": "PayPal", "amazon.com": "Amazon",
    "amazon.fr": "Amazon", "laposte.fr": "La Poste", "ameli.fr": "Ameli",
    "impots.gouv.fr": "impots.gouv.fr", "dgfip.finances.gouv.fr": "DGFiP",
    "netflix.com": "Netflix", "binance.com": "Binance", "dhl.com": "DHL",
    "chronopost.fr": "Chronopost", "bnpparibas.fr": "BNP Paribas",
    "creditmutuel.fr": "Crédit Mutuel", "caisse-epargne.fr": "Caisse d'Épargne",
}

# Tokens de marque pour la détection de combosquatting (sous-chaîne).
_BRAND_TOKENS = {
    "microsoft", "office365", "outlook", "paypal", "apple", "icloud",
    "google", "amazon", "laposte", "ameli", "impots", "netflix", "binance",
    "bnpparibas", "creditmutuel",
}


def registrable_domain(host: str) -> str:
    """Retourne le domaine enregistrable (eTLD+1) de façon offline."""
    host = (host or "").lower().rstrip(".")
    labels = host.split(".")
    if len(labels) <= 2:
        return host
    last2 = ".".join(labels[-2:])
    last3 = ".".join(labels[-3:])
    if last2 in _MULTI_SUFFIXES and len(labels) >= 3:
        return ".".join(labels[-3:])
    return last2 if last2 not in _MULTI_SUFFIXES else last3


def _levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def _has_idn_homoglyph(host: str) -> bool:
    """Punycode (xn--) ou caractères non-ASCII mélangés = risque d'homoglyphe."""
    if "xn--" in host:
        return True
    try:
        decoded = host.encode("ascii").decode("ascii")
        return False
    except UnicodeError:
        return any(unicodedata.category(c).startswith("L") and ord(c) > 127 for c in host)


@dataclass
class DomainAssessment:
    registrable: str
    impersonated_brand: Optional[str] = None
    signals: List[Signal] = None  # type: ignore


def analyze_domain(url: str, seen_domains: Optional[set] = None) -> DomainAssessment:
    host = (urlsplit(url).hostname or "").lower()
    reg = registrable_domain(host)
    signals: List[Signal] = []
    impersonated: Optional[str] = None

    # Domaine légitime connu : aucune alerte d'usurpation.
    if reg in _KNOWN_BRANDS:
        return DomainAssessment(reg, None, signals)

    # 1) Homoglyphe / IDN.
    if _has_idn_homoglyph(host):
        signals.append(Signal(
            code="idn_homoglyph", title="Domaine internationalisé suspect",
            detail="Le domaine utilise des caractères non latins ou du punycode "
                   "(xn--), technique classique d'usurpation par homoglyphes.",
            severity=Severity.HIGH, weight=28, source=_SOURCE,
        ))

    # 2) Sosie d'une marque connue (distance d'édition faible sur le label principal).
    main_label = reg.split(".")[0]
    for brand_reg, label in _KNOWN_BRANDS.items():
        brand_label = brand_reg.split(".")[0]
        dist = _levenshtein(main_label, brand_label)
        if 0 < dist <= 2 and abs(len(main_label) - len(brand_label)) <= 2:
            impersonated = label
            signals.append(Signal(
                code="lookalike_domain",
                title=f"Domaine sosie de « {label} »",
                detail=f"« {reg} » ressemble fortement à « {brand_reg} » "
                       f"(distance {dist}). Typosquatting probable.",
                severity=Severity.CRITICAL, weight=40, source=_SOURCE,
            ))
            break

    # 3) Combosquatting : token de marque présent hors du domaine légitime.
    if impersonated is None:
        for tok in _BRAND_TOKENS:
            if tok in host and not reg.startswith(tok) and reg not in _KNOWN_BRANDS:
                impersonated = tok
                signals.append(Signal(
                    code="brand_combosquatting",
                    title=f"Marque « {tok} » dans un domaine non officiel",
                    detail=f"Le nom « {tok} » apparaît dans « {host} » sans correspondre "
                           f"au domaine officiel. Hameçonnage probable.",
                    severity=Severity.HIGH, weight=30, source=_SOURCE,
                ))
                break

    # 4) Domaine jamais observé localement (heuristique de fraîcheur).
    if seen_domains is not None and reg and reg not in seen_domains:
        signals.append(Signal(
            code="newly_seen_domain", title="Domaine jamais rencontré",
            detail="Ce domaine enregistrable n'a jamais été observé sur cet appareil. "
                   "La nouveauté n'est pas une preuve, mais accroît la prudence.",
            severity=Severity.LOW, weight=6, source=_SOURCE,
        ))

    return DomainAssessment(reg, impersonated, signals)
