"""
Garde anti-SSRF.

Le service BlokQR résout et suit des URL arbitraires fournies par des
inconnus. Sans protection, un attaquant pourrait forger un QR pointant vers
les ressources internes du datacenter (métadonnées cloud 169.254.169.254,
services internes, etc.). Ce module garantit que seules des destinations
publiques et routables sont contactées — c'est le pendant Security by Design
côté serveur.

Stratégie :
  1. Validation du schéma (http/https uniquement).
  2. Résolution DNS explicite de l'hôte.
  3. Rejet de toute adresse privée / loopback / link-local / réservée.
  4. L'IP validée est réutilisée pour la connexion (pinning) afin d'éviter
     les attaques de DNS rebinding (résolution différente entre la
     vérification et la connexion réelle).
"""
from __future__ import annotations

import ipaddress
import socket
from dataclasses import dataclass
from typing import List, Optional
from urllib.parse import urlsplit


class SSRFError(Exception):
    """Levée lorsqu'une URL est jugée non sûre à contacter."""


class HostResolutionError(SSRFError):
    """Hôte introuvable / non résolu (échec DNS) — PAS une tentative SSRF.

    Sous-classe de SSRFError pour rester rétro-compatible avec les appelants qui
    ne filtrent que SSRFError, tout en permettant un traitement distinct (verdict
    prudent au lieu de « malveillant ») là où on sait faire la différence.
    """


@dataclass
class ResolvedHost:
    host: str
    port: int
    scheme: str
    ip_addresses: List[str]
    primary_ip: str


def _is_disallowed_ip(ip: ipaddress._BaseAddress) -> bool:
    """Retourne True si l'adresse appartient à une plage interdite."""
    return (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
        or (isinstance(ip, ipaddress.IPv4Address) and ip in ipaddress.ip_network("169.254.0.0/16"))
        or (isinstance(ip, ipaddress.IPv4Address) and ip in ipaddress.ip_network("100.64.0.0/10"))
        or (isinstance(ip, ipaddress.IPv6Address) and ip.is_site_local)
    )


def host_is_internal(host: str, port: int = 0) -> Optional[bool]:
    """Indique si un hôte tombe dans une plage interdite.

    Pensé pour la garde de rendu (Chromium) : on n'abandonne une requête que
    sur un interne CONFIRMÉ, jamais sur une simple erreur DNS (Chromium gère
    l'échec lui-même, et un sous-domaine injoignable n'est pas une tentative
    SSRF). Retourne True (interne), False (public/routable) ou None (inconnu).
    """
    if not host:
        return None
    try:
        return _is_disallowed_ip(ipaddress.ip_address(host))  # IP littérale
    except ValueError:
        pass
    try:
        infos = socket.getaddrinfo(host, port or None, proto=socket.IPPROTO_TCP)
    except socket.gaierror:
        return None
    for info in infos:
        if _is_disallowed_ip(ipaddress.ip_address(info[4][0])):
            return True  # une seule IP interne suffit (anti-rebinding)
    return False


def resolve_and_validate(
    url: str,
    allowed_schemes: List[str],
    block_private: bool = True,
) -> ResolvedHost:
    """Valide une URL et résout son hôte en adresses publiques.

    Args:
        url: URL à valider.
        allowed_schemes: schémas réseau autorisés.
        block_private: si True, rejette les adresses internes/privées.

    Returns:
        ResolvedHost contenant l'IP primaire à utiliser pour la connexion.

    Raises:
        SSRFError: si l'URL ou la cible résolue est interdite.
    """
    parts = urlsplit(url)
    scheme = (parts.scheme or "").lower()

    if scheme not in allowed_schemes:
        raise SSRFError(f"Schéma non autorisé : '{scheme or '(vide)'}'")

    host = parts.hostname
    if not host:
        raise SSRFError("Hôte absent dans l'URL")

    port = parts.port or (443 if scheme == "https" else 80)

    # Cas où l'hôte est déjà une IP littérale.
    literal_ip: Optional[ipaddress._BaseAddress] = None
    try:
        literal_ip = ipaddress.ip_address(host)
    except ValueError:
        literal_ip = None

    resolved: List[str] = []
    if literal_ip is not None:
        if block_private and _is_disallowed_ip(literal_ip):
            raise SSRFError(f"Adresse IP interdite : {host}")
        resolved = [str(literal_ip)]
    else:
        try:
            infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
        except socket.gaierror as exc:
            raise HostResolutionError(
                f"Résolution DNS impossible pour {host}: {exc}") from exc

        for info in infos:
            sockaddr = info[4]
            ip_str = sockaddr[0]
            ip_obj = ipaddress.ip_address(ip_str)
            if block_private and _is_disallowed_ip(ip_obj):
                # Une seule IP interne suffit à rejeter (anti-rebinding).
                raise SSRFError(
                    f"L'hôte {host} résout vers une adresse interne ({ip_str})"
                )
            resolved.append(ip_str)

        if not resolved:
            raise HostResolutionError(f"Aucune adresse résolue pour {host}")

    return ResolvedHost(
        host=host,
        port=port,
        scheme=scheme,
        ip_addresses=resolved,
        primary_ip=resolved[0],
    )
