"""
Normalisation d'URL et hachage pour le palier de réputation respectueux de
la vie privée.

Principe (aligné sur Google Safe Browsing v5 + Oblivious HTTP, RFC 9458) :

  1. L'URL est canonicalisée de façon déterministe (le même lien produit
     toujours le même hash, côté appareil comme côté serveur).
  2. On génère un ensemble d'expressions « suffixe d'hôte / préfixe de chemin »
     couvrant le domaine et ses sous-chemins.
  3. Chaque expression est hachée en SHA-256 ; on n'expose que les 4 premiers
     octets (préfixe). Le serveur de réputation ne reçoit JAMAIS l'URL complète,
     seulement des préfixes ambigus partagés par de très nombreuses URL
     (k-anonymat). Acheminés via un relais OHTTP, ni l'URL ni l'IP du client ne
     sont révélées au service.

BLAKE3 est utilisé en complément pour l'empreinte stable (contexte temporel /
géographique) : rapide, moderne, résistant aux collisions.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from typing import List
from urllib.parse import urlsplit, urlunsplit

import blake3

# Paramètres de hachage (doivent être identiques côté appareil et côté serveur).
HASH_PREFIX_BYTES = 4  # 4 octets => k-anonymat (préfixe partagé par ~millions d'URL)

# Paramètres de tracking à retirer lors de la normalisation (réduit le bruit
# et évite que deux variantes de la même page produisent des hash différents).
_TRACKING_PARAMS = {
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "gclid", "fbclid", "mc_eid", "mc_cid", "igshid", "ref", "ref_src",
}


@dataclass
class UrlFingerprint:
    """Empreinte normalisée d'une URL."""

    normalized: str
    host: str
    # Hash BLAKE3 complet (hex) de l'URL normalisée — empreinte stable.
    blake3_hex: str
    # Préfixes SHA-256 (4 octets, hex) des expressions hôte/chemin — pour
    # la recherche de réputation k-anonyme.
    expression_prefixes: List[str] = field(default_factory=list)


def canonicalize(url: str) -> str:
    """Canonicalise une URL de manière déterministe.

    - schéma et hôte en minuscules ;
    - retrait du port par défaut, du fragment et des paramètres de tracking ;
    - tri stable des paramètres restants.
    """
    parts = urlsplit(url.strip())
    scheme = (parts.scheme or "http").lower()
    host = (parts.hostname or "").lower().rstrip(".")

    # Retrait des ports par défaut.
    port = parts.port
    netloc = host
    if port and not ((scheme == "http" and port == 80) or (scheme == "https" and port == 443)):
        netloc = f"{host}:{port}"

    # Chemin : conserver tel quel mais normaliser la racine vide.
    path = parts.path or "/"

    # Paramètres : retirer le tracking, trier le reste.
    query_pairs = []
    if parts.query:
        for pair in parts.query.split("&"):
            key = pair.split("=", 1)[0].lower()
            if key and key not in _TRACKING_PARAMS:
                query_pairs.append(pair)
    query = "&".join(sorted(query_pairs))

    return urlunsplit((scheme, netloc, path, query, ""))


def _host_path_expressions(normalized: str) -> List[str]:
    """Génère les expressions suffixe d'hôte / préfixe de chemin.

    Reproduit la logique Safe Browsing : on couvre le domaine exact, ses
    domaines parents (jusqu'à 5 composants), et les préfixes de chemin.
    """
    parts = urlsplit(normalized)
    host = parts.hostname or ""
    path = parts.path or "/"

    # Suffixes d'hôte : exact, puis en retirant un sous-domaine à la fois
    # (max 5 hôtes, conformément à Safe Browsing).
    host_labels = host.split(".")
    hosts: List[str] = []
    if len(host_labels) > 2:
        hosts.append(host)  # hôte exact complet
    # parents : a.b.c.d -> c.d, b.c.d, etc.
    for i in range(max(0, len(host_labels) - 5), len(host_labels) - 1):
        candidate = ".".join(host_labels[i:])
        if candidate and candidate not in hosts:
            hosts.append(candidate)
    if host not in hosts:
        hosts.insert(0, host)

    # Préfixes de chemin : chemin exact, puis remontée jusqu'à la racine
    # (max 6 préfixes, conformément à Safe Browsing).
    segments = [s for s in path.split("/") if s]
    paths: List[str] = ["/"]
    acc = ""
    for seg in segments[:5]:
        acc += "/" + seg
        paths.append(acc)
    if path not in paths:
        paths.append(path)

    expressions = set()
    for h in hosts:
        for p in paths:
            expressions.add(f"{h}{p}")
    return sorted(expressions)


def fingerprint(url: str) -> UrlFingerprint:
    """Calcule l'empreinte complète d'une URL (normalisation + hashs)."""
    normalized = canonicalize(url)
    host = (urlsplit(normalized).hostname or "").lower()

    b3 = blake3.blake3(normalized.encode("utf-8")).hexdigest()

    prefixes: List[str] = []
    for expr in _host_path_expressions(normalized):
        digest = hashlib.sha256(expr.encode("utf-8")).digest()
        prefixes.append(digest[:HASH_PREFIX_BYTES].hex())

    return UrlFingerprint(
        normalized=normalized,
        host=host,
        blake3_hex=b3,
        expression_prefixes=sorted(set(prefixes)),
    )


def full_expression_hash(expression: str) -> str:
    """Hash SHA-256 complet (hex) d'une expression hôte/chemin.

    Utilisé côté serveur pour alimenter la base de réputation : on n'y stocke
    que des hashs, jamais d'URL en clair.
    """
    return hashlib.sha256(expression.encode("utf-8")).hexdigest()


def stable_destination_hash(url: str) -> str:
    """Empreinte BLAKE3 de l'hôte de destination (pour le contexte temporel).

    On hache l'hôte normalisé : un changement d'hôte de destination pour un
    même QR est le signal recherché, sans conserver l'URL en clair.
    """
    host = (urlsplit(canonicalize(url)).hostname or "").lower()
    return blake3.blake3(host.encode("utf-8")).hexdigest()
