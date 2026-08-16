"""
Bac à sable dynamique durci (analyse comportementale via navigateur headless).

Corrections apportées aux faiblesses identifiées :

  1. `--no-sandbox` SUPPRIMÉ par défaut : un détonateur de contenu hostile ne
     doit jamais désactiver le bac à sable du navigateur. Le confinement est
     délégué à l'infrastructure (conteneur rootless + gVisor/Firecracker). Un
     drapeau explicite (déconseillé) reste disponible pour les environnements
     contraints.

  2. Anti-détection : masquage de `navigator.webdriver`, des langues et de
     l'absence de plugins, pour réduire l'identification du scanner par les kits
     qui servent une page bénigne aux robots (le fameux ~80 % non détecté).

  3. Profil unique par défaut, ESCALADE vers un second profil (cloaking) seulement
     en cas de suspicion -> latence réduite sur le cas nominal.

  4. Détection du gating anti-bot (Turnstile/CAPTCHA/défi JS) : un mur anti-robot
     est un signal en soi (technique d'évasion d'analyse).

  5. Sortie réseau via proxy configurable (idéalement résidentiel/mobile) pour ne
     pas être trivialement classé comme datacenter.

Si Playwright est absent, le module se désactive proprement (mode statique).
"""
from __future__ import annotations

import asyncio
import base64
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from typing import List, Optional
from urllib.parse import urlsplit

from app.config import Settings
from app.schemas import Severity, Signal
from app.security.ssrf_guard import host_is_internal

_SOURCE = "dynamic"

try:  # pragma: no cover
    from playwright.async_api import async_playwright  # type: ignore
    _PLAYWRIGHT_AVAILABLE = True
except Exception:  # noqa: BLE001
    _PLAYWRIGHT_AVAILABLE = False

_DESKTOP_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
_MOBILE_UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")

# Script d'initialisation furtif (réduit la détection de headless).
_STEALTH_JS = """
Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
Object.defineProperty(navigator, 'languages', {get: () => ['fr-FR','fr','en-US']});
Object.defineProperty(navigator, 'plugins', {get: () => [1,2,3,4,5]});
window.chrome = window.chrome || { runtime: {} };
"""

_BRAND_FINGERPRINTS = {
    "microsoft": ["microsoft", "office 365", "outlook", "azure", "login.microsoftonline"],
    "google": ["google", "gmail", "accounts.google"],
    "apple": ["apple", "icloud", "apple id"],
    "paypal": ["paypal"], "amazon": ["amazon", "aws"],
    "laposte": ["la poste", "laposte"], "ameli": ["ameli", "assurance maladie"],
    "impots": ["impots", "direction générale des finances"],
    "dhl": ["dhl"], "binance": ["binance"],
}

# Marqueurs de mur anti-robot / défi.
_GATING_MARKERS = [
    "cf-turnstile", "challenges.cloudflare.com", "g-recaptcha", "hcaptcha",
    "/cdn-cgi/challenge", "just a moment", "verifying you are human",
    "enable javascript and cookies to continue",
]

# Marqueurs de page intermédiaire / interstitiel de redirection (raccourcisseurs).
# Sur une page de transit, l'attribution d'une marque et la présence d'un champ
# mot de passe ne sont PAS fiables : on évite d'en tirer une accusation
# d'usurpation (faux positif classique des raccourcisseurs type encurtador).
_INTERSTITIAL_MARKERS = [
    "redirecionamento", "redirecting", "you are being redirected",
    "redirection en cours", "vous allez être redirigé", "veuillez patienter",
    "please wait while we redirect", "continuer vers le site", "continue to the site",
]
_INTERSTITIAL_PATH_HINTS = [
    "/redirecionamento", "/redirect", "/go/", "/out/", "/away", "/r/",
]


@dataclass
class DynamicResult:
    available: bool = False
    final_url: Optional[str] = None
    js_redirects: List[str] = field(default_factory=list)
    cloaking_detected: bool = False
    login_form_detected: bool = False
    impersonated_brand: Optional[str] = None
    gating_detected: bool = False
    signals: List[Signal] = field(default_factory=list)
    screenshot_b64: Optional[str] = None


def _launch_args(settings: Settings) -> List[str]:
    args = ["--disable-dev-shm-usage", "--disable-gpu", "--disable-extensions",
            "--disable-blink-features=AutomationControlled"]
    if getattr(settings, "sandbox_disable_chromium_sandbox", False):
        # Déconseillé : uniquement pour environnements sans isolation externe.
        args.append("--no-sandbox")
    return args


def _proxy(settings: Settings):
    url = (getattr(settings, "egress_proxy_url", "") or "").strip()
    # On n'accepte qu'une URL de proxy valide. Toute autre valeur (commentaire
    # mal collé dans .env, espace, etc.) est ignorée : un proxy invalide ne doit
    # JAMAIS casser le bac à sable (sinon tous les verdicts tombent en UNKNOWN).
    if url.lower().startswith(("http://", "https://", "socks5://",
                               "socks4://", "socks://")):
        return {"server": url}
    return None


async def _install_egress_guard(context) -> None:
    """Garde anti-SSRF AU NIVEAU DU RENDU.

    Le résolveur valide déjà la navigation principale, mais une page hostile peut,
    une fois chargée, tenter de joindre une cible interne via JS/iframe/img/XHR
    (par ex. les métadonnées cloud en lien-local, ou un service privé). On
    intercepte donc TOUTES les requêtes du contexte et on abandonne celles qui
    visent un hôte interne CONFIRMÉ ; le public et l'inconnu passent (Chromium
    gère ses propres échecs, et on ne casse pas un rendu sur une simple erreur DNS).
    """
    cache: dict = {}

    async def _route(route):
        try:
            host = (urlsplit(route.request.url).hostname or "").lower()
            verdict = cache.get(host, "?")
            if verdict == "?":
                # getaddrinfo est bloquant -> hors boucle d'événements.
                verdict = await asyncio.to_thread(host_is_internal, host)
                cache[host] = verdict
            if verdict is True:
                await route.abort()
                return
        except Exception:
            pass
        try:
            await route.continue_()
        except Exception:
            pass

    await context.route("**/*", _route)


async def _load_profile(browser, url: str, ua: str, accept_lang: str,
                        timeout: float, settings: Settings,
                        capture_screenshot: bool = False, settle_ms: int = 4000):
    # Le navigateur est fourni par l'appelant (chaud/mutualisé via le pool, ou
    # lancé à la volée en mode strict). Le profil ne possède QUE son contexte :
    # l'isolation repose sur un contexte éphémère (cookies/stockage cloisonnés)
    # fermé en fin de profil ; le navigateur n'est JAMAIS fermé ici.
    redirects: List[str] = []
    screenshot_b64: Optional[str] = None
    context = await browser.new_context(
        user_agent=ua, locale=accept_lang.split(",")[0],
        extra_http_headers={"Accept-Language": accept_lang},
        java_script_enabled=True, ignore_https_errors=True,
        viewport={"width": 412, "height": 915})
    try:
        await context.add_init_script(_STEALTH_JS)
        await _install_egress_guard(context)
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
        # -> ces pages plafonnaient systématiquement au timeout.
        await page.goto(url, wait_until="domcontentloaded", timeout=int(timeout * 1000))
        # Suit les redirections JS/meta (raccourcisseurs, pages intermédiaires)
        # jusqu'à stabilisation de l'URL, dans une enveloppe stricte : on analyse
        # ainsi la VRAIE destination et non la page de transit.
        final_url = await _settle(page, settle_ms)
        text = (await page.content()).lower()
        title = (await page.title()).lower()
        has_password = await page.evaluate(
            "() => !!document.querySelector('input[type=password]')")
        if capture_screenshot:
            raw = await page.screenshot(type="png", full_page=False)
            screenshot_b64 = base64.b64encode(raw).decode("ascii")
        return final_url, text, title, has_password, redirects, screenshot_b64, blocked_hosts
    finally:
        await context.close()


def _detect_brand(text: str, title: str) -> Optional[str]:
    hay = f"{title} {text[:20000]}"
    for brand, markers in _BRAND_FINGERPRINTS.items():
        if any(m in hay for m in markers):
            return brand
    return None


def _content_divergence(a: str, b: str) -> float:
    if not a and not b:
        return 0.0
    sa, sb = set(a.split()), set(b.split())
    if not sa or not sb:
        return 1.0
    return 1.0 - (len(sa & sb) / len(sa | sb))


def _gating(text: str) -> bool:
    return any(m in text for m in _GATING_MARKERS)


def _is_interstitial(url: str, text: str, has_js_redirect: bool) -> bool:
    """La page est-elle une simple page de transit/redirection ?

    Précis pour éviter les faux négatifs : un marqueur textuel explicite suffit ;
    sinon on exige une redirection JS observée ET (un chemin de redirection connu
    OU un contenu quasi vide). Une vraie page d'hameçonnage statique (sans
    redirection, avec un vrai formulaire) n'est donc pas classée interstitiel.
    """
    u = (url or "").lower()
    if any(m in text for m in _INTERSTITIAL_MARKERS):
        return True
    if has_js_redirect and (
        any(h in u for h in _INTERSTITIAL_PATH_HINTS) or len(text.strip()) < 2000
    ):
        return True
    return False


async def _settle(page, total_ms: int) -> str:
    """Suit les redirections JS/meta jusqu'à stabilisation de l'URL (borné).

    Les raccourcisseurs passent par une page intermédiaire qui redirige ensuite
    en JavaScript/meta-refresh. Sans cette attente active, l'analyse s'arrête sur
    l'interstitiel (faux positifs d'usurpation). On suit donc les sauts successifs
    jusqu'à stabilisation, dans une enveloppe de temps stricte.
    """
    # Fenêtre d'inactivité réseau initiale (bornée) : capte les redirections
    # immédiates sans bloquer sur les pages gardant des connexions ouvertes.
    try:
        await page.wait_for_load_state("networkidle", timeout=min(2000, total_ms))
    except Exception:
        pass
    remaining = total_ms
    step = 1500
    while remaining > 0:
        before = page.url
        try:
            await page.wait_for_url(lambda u: u != before, timeout=min(step, remaining))
        except Exception:
            break  # plus de navigation : URL stable
        remaining -= step
        try:
            await page.wait_for_load_state("domcontentloaded", timeout=1000)
        except Exception:
            pass
    return page.url


class _BrowserPool:
    """Maintient un navigateur Chromium « chaud » réutilisé entre analyses (M2c).

    Jusqu'ici, chaque analyse approfondie payait un DOUBLE coût à froid :
    démarrage du driver Playwright (`async_playwright()`) + lancement d'un
    Chromium neuf par profil. Ce pool démarre le driver et le navigateur UNE
    fois puis les réutilise. L'isolation est préservée car chaque analyse
    obtient un CONTEXTE neuf (cookies/stockage cloisonnés, fermé en fin de
    profil). Le navigateur est recyclé périodiquement (nombre d'usages, âge)
    ou s'il s'est déconnecté (page hostile l'ayant fait planter). La
    concurrence est bornée par un sémaphore, et le recyclage n'a lieu qu'à
    l'arrêt (aucune analyse en vol) pour ne jamais fermer un navigateur utilisé.
    """

    def __init__(self) -> None:
        self._pw = None
        self._browser = None
        self._lock = asyncio.Lock()
        self._sem: Optional[asyncio.Semaphore] = None
        self._uses = 0
        self._launched_at = 0.0
        self._inflight = 0

    def _get_sem(self, settings: Settings) -> asyncio.Semaphore:
        if self._sem is None:
            n = max(1, int(getattr(settings, "sandbox_pool_concurrency", 2)))
            self._sem = asyncio.Semaphore(n)
        return self._sem

    def _alive(self) -> bool:
        b = self._browser
        if b is None:
            return False
        try:
            return bool(b.is_connected())
        except Exception:  # noqa: BLE001
            return False

    async def _close_browser_locked(self) -> None:
        b, self._browser = self._browser, None
        if b is not None:
            try:
                await b.close()
            except Exception:  # noqa: BLE001
                pass

    async def _launch_locked(self, settings: Settings):
        if self._pw is None:
            self._pw = await async_playwright().start()
        self._browser = await self._pw.chromium.launch(
            headless=True, args=_launch_args(settings), proxy=_proxy(settings))
        self._uses = 0
        self._launched_at = time.monotonic()

    async def _maybe_recycle_locked(self, settings: Settings) -> None:
        if self._inflight > 0 or self._browser is None:
            return
        max_uses = int(getattr(settings, "sandbox_pool_max_uses", 40))
        max_age = float(getattr(settings, "sandbox_pool_max_age_seconds", 900.0))
        too_old = max_age > 0 and (time.monotonic() - self._launched_at) >= max_age
        too_used = max_uses > 0 and self._uses >= max_uses
        if too_old or too_used or not self._alive():
            await self._close_browser_locked()

    async def _ensure_locked(self, settings: Settings):
        if not self._alive():
            await self._close_browser_locked()
            await self._launch_locked(settings)
        return self._browser

    @asynccontextmanager
    async def lease(self, settings: Settings):
        sem = self._get_sem(settings)
        await sem.acquire()
        try:
            async with self._lock:
                await self._maybe_recycle_locked(settings)
                browser = await self._ensure_locked(settings)
                self._inflight += 1
                self._uses += 1
            try:
                yield browser
            finally:
                async with self._lock:
                    self._inflight -= 1
        finally:
            sem.release()

    async def prewarm(self, settings: Settings) -> None:
        async with self._lock:
            await self._ensure_locked(settings)

    async def shutdown(self) -> None:
        async with self._lock:
            await self._close_browser_locked()
            if self._pw is not None:
                try:
                    await self._pw.stop()
                except Exception:  # noqa: BLE001
                    pass
                self._pw = None


_POOL = _BrowserPool()


async def _run_profiles(browser, final_url: str, settings: Settings,
                        capture: bool, result: DynamicResult) -> None:
    """Exécute les profils d'analyse sur un navigateur fourni (pool ou éphémère)."""
    # --- Profil mobile (nominal) ------------------------------------
    (m_url, m_text, m_title, m_pw, m_redirs, m_shot, m_blocked) = await _load_profile(
        browser, final_url, _MOBILE_UA, "fr-FR,fr;q=0.9",
        settings.dynamic_timeout_seconds, settings, capture_screenshot=capture)

    result.final_url = m_url
    result.js_redirects = [u for u in m_redirs if u != final_url]
    result.screenshot_b64 = m_shot
    result.login_form_detected = bool(m_pw)
    brand = _detect_brand(m_text, m_title)
    host = (urlsplit(result.final_url or final_url).hostname or "").lower()
    # La destination finale est-elle encore une simple page de transit ?
    interstitial = _is_interstitial(
        result.final_url or final_url, m_text, bool(result.js_redirects))
    if interstitial:
        # Page intermédiaire non franchie : la VRAIE destination n'a pas pu
        # être observée. On ne certifie donc PAS « sûr » (fail-safe assuré
        # côté pipeline, qui bascule un verdict safe vers unknown).
        result.signals.append(Signal(
            code="unresolved_redirect",
            title="Redirection non résolue",
            detail="La cible passe par une page intermédiaire dont la destination "
                   "finale n'a pas pu être vérifiée automatiquement. Ouverture en "
                   "bac à sable recommandée si vous attendiez ce lien.",
            severity=Severity.LOW, weight=0, source=_SOURCE))

    if _gating(m_text):
        result.gating_detected = True
        result.signals.append(Signal(
            code="antibot_gating", title="Mur anti-robot détecté",
            detail="La page impose un défi (Turnstile/CAPTCHA/Cloudflare). "
                   "Souvent utilisé pour bloquer l'analyse automatisée et "
                   "ne révéler le contenu malveillant qu'aux humains.",
            severity=Severity.MEDIUM, weight=18, source=_SOURCE))

    if result.js_redirects:
        result.signals.append(Signal(
            code="js_redirect", title="Redirection JavaScript observée",
            detail="La page redirige via script, invisible à l'analyse HTTP statique.",
            severity=Severity.MEDIUM, weight=12, source=_SOURCE))

    # --- Escalade : second profil seulement si suspicion -------------
    suspicious = (m_pw or brand or result.gating_detected
                  or bool(result.js_redirects)
                  or getattr(settings, "always_dual_profile", False))
    if suspicious:
        (d_url, d_text, d_title, d_pw, _d_r, _d_s, d_blocked) = await _load_profile(
            browser, final_url, _DESKTOP_UA, "en-US,en;q=0.9",
            settings.dynamic_timeout_seconds, settings, settle_ms=1500)
        m_blocked = list(dict.fromkeys(m_blocked + d_blocked))
        divergence = _content_divergence(m_text, d_text)
        if divergence >= 0.65 or (m_url != d_url):
            result.cloaking_detected = True
            result.signals.append(Signal(
                code="cloaking_detected", title="Cloaking détecté",
                detail=f"Contenu fortement divergent entre profils mobile et bureau "
                       f"(divergence {divergence:.0%}). Évasion classique du quishing.",
                severity=Severity.HIGH, weight=26, source=_SOURCE))
        if not brand:
            brand = _detect_brand(d_text, d_title)

    # --- Usurpation de page de connexion -----------------------------
    # On n'accuse JAMAIS une page de transit (interstitiel de
    # raccourcisseur) : marque et formulaire n'y sont pas fiables.
    if (result.login_form_detected and brand and brand not in host
            and not interstitial):
        result.impersonated_brand = brand
        result.signals.append(Signal(
            code="login_impersonation",
            title=f"Page de connexion imitant « {brand} »",
            detail=f"Formulaire de mot de passe + identité visuelle « {brand} » "
                   f"sur un domaine non officiel ({host}). Hameçonnage très probable.",
            severity=Severity.CRITICAL, weight=38, source=_SOURCE))
    elif result.login_form_detected and not interstitial:
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
            severity=Severity.HIGH, weight=30, source=_SOURCE))


async def analyze_dynamic(final_url: str, settings: Settings,
                          want_screenshot: bool = False) -> DynamicResult:
    if not (_PLAYWRIGHT_AVAILABLE and settings.enable_dynamic_sandbox):
        return DynamicResult(available=False)

    capture = bool(want_screenshot and settings.enable_screenshot)
    result = DynamicResult(available=True)
    try:
        if getattr(settings, "sandbox_prewarm", True):
            # Navigateur chaud mutualisé : coût à froid supprimé, isolation
            # garantie par un contexte neuf par profil (cf. _load_profile).
            async with _POOL.lease(settings) as browser:
                await _run_profiles(browser, final_url, settings, capture, result)
        else:
            # Mode strict : navigateur jeté après chaque analyse (isolation max,
            # au prix du démarrage à froid). Conserve l'ancien comportement.
            async with async_playwright() as pw:
                browser = await pw.chromium.launch(
                    headless=True, args=_launch_args(settings), proxy=_proxy(settings))
                try:
                    await _run_profiles(browser, final_url, settings, capture, result)
                finally:
                    await browser.close()
    except Exception as exc:  # noqa: BLE001
        result.signals.append(Signal(
            code="dynamic_error", title="Analyse dynamique incomplète",
            detail=f"Le rendu headless a échoué ({exc.__class__.__name__}).",
            severity=Severity.INFO, weight=0, source=_SOURCE))
    return result


async def prewarm_sandbox(settings: Settings) -> bool:
    """Pré-chauffe le navigateur au démarrage (best-effort, ne bloque jamais).

    Retourne True si un navigateur chaud est prêt. Toute erreur est avalée :
    un échec de pré-chauffage ne doit pas empêcher l'application de démarrer
    (le pool relancera à la première analyse, en mode dégradé à froid).
    """
    if not (_PLAYWRIGHT_AVAILABLE and settings.enable_dynamic_sandbox
            and getattr(settings, "sandbox_prewarm", True)):
        return False
    try:
        await _POOL.prewarm(settings)
        return True
    except Exception:  # noqa: BLE001
        return False


async def shutdown_sandbox() -> None:
    """Ferme proprement le navigateur chaud et le driver (arrêt applicatif)."""
    try:
        await _POOL.shutdown()
    except Exception:  # noqa: BLE001
        pass
