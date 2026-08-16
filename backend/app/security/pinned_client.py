"""
Transport httpx épinglé sur l'IP validée (anti DNS rebinding).

Problème : `resolve_and_validate` (ssrf_guard) résout l'hôte, vérifie que
l'IP est publique, puis on faisait `client.get(url)` — et httpx **re-résolvait**
l'hôte de son côté. Entre les deux résolutions, un DNS contrôlé par
l'attaquant peut renvoyer une IP publique (validation) puis une IP interne
(connexion) : c'est le DNS rebinding (TOCTOU).

Solution : on ne change RIEN à la couche TLS (URL, SNI, vérification de
certificat restent gérés normalement par httpx contre le nom d'hôte). On
remplace uniquement l'adresse de connexion TCP par l'IP déjà validée, via le
backend réseau de httpcore. La socket est donc ouverte vers l'IP épinglée
(aucune seconde résolution), tandis que le certificat est toujours vérifié
contre le nom d'hôte d'origine — exactement comme une requête httpx normale.

Usage :
    pin: dict[str, str] = {}
    transport = make_pinned_transport(pin)
    async with httpx.AsyncClient(transport=transport, follow_redirects=False,
                                 limits=httpx.Limits(max_keepalive_connections=0),
                                 ...) as client:
        # avant chaque requête, après validation SSRF :
        pin[resolved.host] = resolved.primary_ip
        resp = await client.get(url)

Le dict `pin` est partagé (par référence) avec le backend : il suffit de le
mettre à jour avant chaque saut. `max_keepalive_connections=0` garantit qu'une
nouvelle connexion (donc un nouveau connect_tcp épinglé) est ouverte à chaque
requête, y compris après une redirection vers un autre hôte.
"""
from __future__ import annotations

from typing import Dict, Optional

import httpcore
import httpx


class _PinnedBackend(httpcore.AnyIOBackend):
    """Backend réseau qui force l'adresse de connexion TCP à l'IP validée."""

    def __init__(self, pin: Dict[str, str]) -> None:
        super().__init__()
        self._pin = pin

    async def connect_tcp(self, host, port, timeout=None, local_address=None,
                          socket_options=None):
        # `host` est le nom d'hôte d'origine (httpcore le décode en str). On le
        # remplace par l'IP pré-validée si elle est connue ; sinon comportement
        # normal (utile pour un hôte non encore épinglé).
        target = self._pin.get(host, host)
        return await super().connect_tcp(
            target, port, timeout=timeout,
            local_address=local_address, socket_options=socket_options,
        )


def make_pinned_transport(pin: Dict[str, str],
                          retries: int = 0) -> httpx.AsyncHTTPTransport:
    """Construit un transport httpx dont les connexions sont épinglées via `pin`.

    `pin` est un dict {hostname: ip} partagé par référence : le mettre à jour
    avant chaque requête. Fail-closed : si l'attribut interne attendu de
    httpcore disparaît (changement de version), on lève une exception plutôt
    que de perdre silencieusement le pinning (= protection SSRF).
    """
    transport = httpx.AsyncHTTPTransport(retries=retries)
    pool = transport._pool
    if not hasattr(pool, "_network_backend"):
        raise RuntimeError(
            "httpcore: attribut '_network_backend' introuvable — pinning SSRF "
            "impossible. Refus de continuer sans protection (fail-closed)."
        )
    pool._network_backend = _PinnedBackend(pin)
    return transport
