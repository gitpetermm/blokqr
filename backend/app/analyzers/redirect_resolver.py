"""
Résolveur de chaîne de redirections (analyse réseau, sans exécution JS).

Suit l'intégralité des rebonds HTTP (301/302/303/307/308) et les
redirections meta-refresh HTML, jusqu'à la destination finale. Chaque saut
est revalidé par la garde anti-SSRF (un raccourcisseur public peut rediriger
vers une ressource interne).

Cette étape est entièrement déportée sur le serveur : le téléphone ne contacte
jamais ces URL. Le client (mobile) reste donc protégé même si la chaîne mène
à un exploit navigateur.
"""
from __future__ import annotations

import re
from time import monotonic
from typing import List, Optional, Tuple

import httpx

from app.config import Settings
from app.schemas import RedirectHop, Severity, Signal
from app.security.ssrf_guard import HostResolutionError, SSRFError, resolve_and_validate
from app.security.pinned_client import make_pinned_transport

_SOURCE = "redirect"
_META_REFRESH_RE = re.compile(
    r"""<meta[^>]+http-equiv=["']?refresh["']?[^>]+content=["'][^"']*url=([^"'>\s]+)""",
    re.IGNORECASE,
)
# User-Agent mobile réaliste : certaines pages de cloaking ne déclenchent leur
# charge utile que pour des navigateurs mobiles.
_MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)


async def resolve_chain(
    start_url: str, settings: Settings
) -> Tuple[List[RedirectHop], Optional[str], List[Signal]]:
    """Suit la chaîne de redirections.

    Returns:
        (hops, final_url, signals)
    """
    hops: List[RedirectHop] = []
    signals: List[Signal] = []
    current = start_url
    seen: set[str] = set()

    # Connexions épinglées sur l'IP validée (anti DNS rebinding) : le dict est
    # mis à jour avant chaque saut, après la validation SSRF.
    pin: dict[str, str] = {}
    transport = make_pinned_transport(pin)
    headers = {"User-Agent": _MOBILE_UA, "Accept": "text/html,*/*"}

    # Budget mur d'horloge : borne la résolution même face à une cible lente ou
    # une longue chaîne, pour garder le chemin rapide sous le budget global.
    deadline = monotonic() + settings.redirect_budget_seconds

    async with httpx.AsyncClient(
        follow_redirects=False,
        timeout=settings.http_timeout_seconds,
        transport=transport,
        headers=headers,
        max_redirects=0,
        # Pas de réutilisation de connexion : chaque requête ré-ouvre une socket
        # (donc un connect_tcp épinglé), y compris après redirection.
        limits=httpx.Limits(max_keepalive_connections=0),
    ) as client:
        for index in range(settings.max_redirect_hops + 1):
            if monotonic() > deadline:
                signals.append(Signal(
                    code="unresolved_redirect",
                    title="Résolution des redirections interrompue (délai dépassé)",
                    detail="La chaîne de redirections n'a pas pu être entièrement "
                           "résolue dans le budget imparti ; verdict prudent rendu "
                           "par sécurité. Vous pouvez réessayer.",
                    severity=Severity.LOW, weight=0, source=_SOURCE,
                ))
                break
            if current in seen:
                signals.append(Signal(
                    code="redirect_loop",
                    title="Boucle de redirection détectée",
                    detail="La chaîne de redirections boucle sur elle-même.",
                    severity=Severity.MEDIUM, weight=10, source=_SOURCE,
                ))
                break
            seen.add(current)

            # Revalidation SSRF à CHAQUE saut.
            try:
                resolved = resolve_and_validate(
                    current,
                    allowed_schemes=settings.allowed_url_schemes,
                    block_private=settings.block_private_networks,
                )
            except HostResolutionError:
                # Domaine inexistant / injoignable : ce n'est PAS une attaque
                # SSRF. Verdict prudent (jamais « malveillant » sur un lien mort).
                signals.append(Signal(
                    code="domain_unresolved",
                    title="Domaine introuvable",
                    detail="Cette destination ne résout vers aucune adresse "
                           "(lien probablement mort ou erroné). Analyse "
                           "impossible : verdict prudent rendu par sécurité.",
                    severity=Severity.LOW, weight=6, source=_SOURCE,
                ))
                break
            except SSRFError as exc:
                signals.append(Signal(
                    code="ssrf_blocked_hop",
                    title="Redirection vers une ressource interne bloquée",
                    detail=str(exc),
                    severity=Severity.CRITICAL, weight=40, source=_SOURCE,
                ))
                break

            # Épinglage : la connexion ira sur l'IP validée, pas sur une
            # nouvelle résolution DNS (fermeture de la fenêtre de rebinding).
            pin[resolved.host] = resolved.primary_ip

            try:
                resp = await client.get(current)
            except httpx.HTTPError as exc:
                hops.append(RedirectHop(
                    index=index, url=current, status_code=None,
                    kind="error", resolved_ip=resolved.primary_ip,
                ))
                signals.append(Signal(
                    code="hop_unreachable",
                    title="Saut injoignable",
                    detail=f"Erreur réseau sur {current}: {exc.__class__.__name__}",
                    severity=Severity.LOW, weight=4, source=_SOURCE,
                ))
                break

            server = resp.headers.get("server")
            location = resp.headers.get("location")

            if resp.is_redirect and location:
                next_url = str(resp.next_request.url) if resp.next_request else location
                hops.append(RedirectHop(
                    index=index, url=current, status_code=resp.status_code,
                    kind="http", resolved_ip=resolved.primary_ip, server=server,
                ))
                # Changement de schéma https -> http = signal de rétrogradation.
                if current.startswith("https://") and next_url.startswith("http://"):
                    signals.append(Signal(
                        code="tls_downgrade",
                        title="Rétrogradation HTTPS vers HTTP",
                        detail="Une redirection abandonne le chiffrement TLS.",
                        severity=Severity.MEDIUM, weight=14, source=_SOURCE,
                    ))
                current = next_url
                continue

            # Pas de redirection HTTP : on cherche un meta-refresh dans le HTML.
            content_type = resp.headers.get("content-type", "")
            body_snippet = ""
            if "text/html" in content_type:
                body_snippet = resp.text[:65536]
                m = _META_REFRESH_RE.search(body_snippet)
                if m:
                    meta_target = m.group(1).strip()
                    hops.append(RedirectHop(
                        index=index, url=current, status_code=resp.status_code,
                        kind="meta-refresh", resolved_ip=resolved.primary_ip,
                        server=server,
                    ))
                    signals.append(Signal(
                        code="meta_refresh_redirect",
                        title="Redirection meta-refresh",
                        detail="Redirection furtive via balise HTML meta-refresh.",
                        severity=Severity.MEDIUM, weight=10, source=_SOURCE,
                    ))
                    # Résolution relative basique.
                    if meta_target.startswith("/"):
                        from urllib.parse import urljoin
                        meta_target = urljoin(current, meta_target)
                    current = meta_target
                    continue

            # Destination finale atteinte.
            hops.append(RedirectHop(
                index=index, url=current, status_code=resp.status_code,
                kind="final", resolved_ip=resolved.primary_ip, server=server,
            ))
            return hops, current, signals

        else:
            signals.append(Signal(
                code="too_many_redirects",
                title="Trop de redirections",
                detail=f"Limite de {settings.max_redirect_hops} sauts atteinte.",
                severity=Severity.MEDIUM, weight=12, source=_SOURCE,
            ))

    final = hops[-1].url if hops else start_url

    # Signal sur la longueur de la chaîne.
    redirect_hops = [h for h in hops if h.kind in ("http", "meta-refresh")]
    if len(redirect_hops) >= 3:
        signals.append(Signal(
            code="long_redirect_chain",
            title=f"Chaîne de redirection longue ({len(redirect_hops)} rebonds)",
            detail="Les chaînes multiples servent souvent à brouiller la traçabilité.",
            severity=Severity.LOW, weight=4 * min(len(redirect_hops), 3),
            source=_SOURCE,
        ))

    return hops, final, signals
