import ast

FILES = {
    'app/security/ssrf_guard.py': (
        'host_is_internal',
        [
            (
                '''def resolve_and_validate(''',
                '''def host_is_internal(host: str, port: int = 0) -> Optional[bool]:
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


def resolve_and_validate(''',
            ),
        ],
    ),
    'app/analyzers/dynamic_sandbox.py': (
        'host_is_internal',
        [
            (
                '''from app.config import Settings
from app.schemas import Severity, Signal''',
                '''from app.config import Settings
from app.schemas import Severity, Signal
from app.security.ssrf_guard import host_is_internal''',
            ),
            (
                '''        await context.add_init_script(_STEALTH_JS)
        page = await context.new_page()
        page.on("framenavigated", lambda fr: redirects.append(fr.url)
                if fr == page.main_frame else None)''',
                '''        await context.add_init_script(_STEALTH_JS)
        page = await context.new_page()

        # --- Garde anti-SSRF du rendu ------------------------------------
        # Chromium suit seul les redirections JS/meta et charge les
        # sous-ressources, hors de la garde du résolveur. On intercepte donc
        # CHAQUE requête : toute cible résolvant vers une IP interne confirmée
        # (LAN, hôte, autres conteneurs, 127.0.0.1, 169.254.169.254…) est
        # abandonnée. On ne bloque jamais sur une simple erreur DNS.
        blocked_hosts: List[str] = []
        _ssrf_cache: dict = {}

        async def _ssrf_route(route):
            try:
                parts = urlsplit(route.request.url)
                if (parts.scheme or "").lower() not in ("http", "https"):
                    await route.continue_()
                    return
                host = parts.hostname or ""
                verdict = _ssrf_cache.get(host)
                if verdict is None:
                    verdict = await asyncio.to_thread(
                        host_is_internal, host, parts.port or 0)
                    _ssrf_cache[host] = verdict
                if verdict is True:
                    if host not in blocked_hosts:
                        blocked_hosts.append(host)
                    await route.abort()
                else:
                    await route.continue_()
            except Exception:  # noqa: BLE001 - ne jamais casser le rendu
                try:
                    await route.continue_()
                except Exception:
                    pass

        if getattr(settings, "block_private_networks", True):
            await page.route("**/*", _ssrf_route)

        page.on("framenavigated", lambda fr: redirects.append(fr.url)
                if fr == page.main_frame else None)
        # domcontentloaded : rend la main dès que le DOM est prêt. networkidle
        # attend 500 ms d'inactivité réseau, jamais atteint sur les pages qui
        # gardent des connexions ouvertes (pub, long-polling, pages d'hameçonnage)
        # -> ces pages plafonnaient systématiquement au timeout.''',
            ),
            (
                '''        return final_url, text, title, has_password, redirects, screenshot_b64''',
                '''        return final_url, text, title, has_password, redirects, screenshot_b64, blocked_hosts''',
            ),
            (
                '''            (m_url, m_text, m_title, m_pw, m_redirs, m_shot) = await _load_profile(
                pw, final_url, _MOBILE_UA, "fr-FR,fr;q=0.9",
                settings.dynamic_timeout_seconds, settings, capture_screenshot=capture)''',
                '''            (m_url, m_text, m_title, m_pw, m_redirs, m_shot, m_blocked) = await _load_profile(
                pw, final_url, _MOBILE_UA, "fr-FR,fr;q=0.9",
                settings.dynamic_timeout_seconds, settings, capture_screenshot=capture)''',
            ),
            (
                '''                (d_url, d_text, d_title, d_pw, _d_r, _d_s) = await _load_profile(
                    pw, final_url, _DESKTOP_UA, "en-US,en;q=0.9",
                    settings.dynamic_timeout_seconds, settings, settle_ms=1500)''',
                '''                (d_url, d_text, d_title, d_pw, _d_r, _d_s, d_blocked) = await _load_profile(
                    pw, final_url, _DESKTOP_UA, "en-US,en;q=0.9",
                    settings.dynamic_timeout_seconds, settings, settle_ms=1500)
                m_blocked = list(dict.fromkeys(m_blocked + d_blocked))''',
            ),
            (
                '''            elif result.login_form_detected and not interstitial:
                result.signals.append(Signal(
                    code="password_form", title="Formulaire de mot de passe détecté",
                    detail="La page demande un mot de passe : vérifiez l'authenticité du domaine.",
                    severity=Severity.MEDIUM, weight=12, source=_SOURCE))''',
                '''            elif result.login_form_detected and not interstitial:
                result.signals.append(Signal(
                    code="password_form", title="Formulaire de mot de passe détecté",
                    detail="La page demande un mot de passe : vérifiez l'authenticité du domaine.",
                    severity=Severity.MEDIUM, weight=12, source=_SOURCE))

            # --- Tentative d'accès à une ressource interne (SSRF) ------------
            if m_blocked:
                result.signals.append(Signal(
                    code="ssrf_blocked_render",
                    title="Tentative d'accès à une ressource interne",
                    detail="La page a tenté de contacter une ou plusieurs adresses "
                           f"internes ({', '.join(m_blocked[:5])}). Requêtes bloquées. "
                           "Comportement typique d'une attaque SSRF / de reconnaissance.",
                    severity=Severity.HIGH, weight=30, source=_SOURCE))''',
            ),
        ],
    ),
}

for path, (marker, pairs) in FILES.items():
    s = open(path, encoding='utf-8').read()
    if marker in s:
        print(f'[skip] {path} : deja patche'); continue
    for i, (old, new) in enumerate(pairs, 1):
        n = s.count(old)
        assert n == 1, f'[ABANDON] {path} ancre #{i} trouvee {n} fois - rien ecrit'
        s = s.replace(old, new, 1)
    ast.parse(s)
    open(path, 'w', encoding='utf-8').write(s)
    print(f'[ok] {path} patche')
