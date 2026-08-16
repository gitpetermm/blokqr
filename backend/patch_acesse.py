import ast

PATH = "app/analyzers/dynamic_sandbox.py"
s = open(PATH, encoding="utf-8").read()

if "_INTERSTITIAL_MARKERS" in s:
    print("[skip] deja patche - rien a faire")
    raise SystemExit(0)

PAIRS = [
    (
        '''    "/cdn-cgi/challenge", "just a moment", "verifying you are human",
    "enable javascript and cookies to continue",
]''',
        '''    "/cdn-cgi/challenge", "just a moment", "verifying you are human",
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
]''',
    ),
    (
        '''def _gating(text: str) -> bool:
    return any(m in text for m in _GATING_MARKERS)''',
        '''def _gating(text: str) -> bool:
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
    return page.url''',
    ),
    (
        '''async def _load_profile(playwright, url: str, ua: str, accept_lang: str,
                        timeout: float, settings: Settings,
                        capture_screenshot: bool = False):''',
        '''async def _load_profile(playwright, url: str, ua: str, accept_lang: str,
                        timeout: float, settings: Settings,
                        capture_screenshot: bool = False, settle_ms: int = 4000):''',
    ),
    (
        '''        await page.goto(url, wait_until="domcontentloaded", timeout=int(timeout * 1000))
        try:
            await page.wait_for_load_state("networkidle", timeout=2500)
        except Exception:
            pass
        final_url = page.url''',
        '''        await page.goto(url, wait_until="domcontentloaded", timeout=int(timeout * 1000))
        # Suit les redirections JS/meta (raccourcisseurs, pages intermédiaires)
        # jusqu'à stabilisation de l'URL, dans une enveloppe stricte : on analyse
        # ainsi la VRAIE destination et non la page de transit.
        final_url = await _settle(page, settle_ms)''',
    ),
    (
        '''            brand = _detect_brand(m_text, m_title)
            host = (urlsplit(result.final_url or final_url).hostname or "").lower()''',
        '''            brand = _detect_brand(m_text, m_title)
            host = (urlsplit(result.final_url or final_url).hostname or "").lower()
            # La destination finale est-elle encore une simple page de transit ?
            interstitial = _is_interstitial(
                result.final_url or final_url, m_text, bool(result.js_redirects))''',
    ),
    (
        '''                (d_url, d_text, d_title, d_pw, _d_r, _d_s) = await _load_profile(
                    pw, final_url, _DESKTOP_UA, "en-US,en;q=0.9",
                    settings.dynamic_timeout_seconds, settings)''',
        '''                (d_url, d_text, d_title, d_pw, _d_r, _d_s) = await _load_profile(
                    pw, final_url, _DESKTOP_UA, "en-US,en;q=0.9",
                    settings.dynamic_timeout_seconds, settings, settle_ms=1500)''',
    ),
    (
        '''            # --- Usurpation de page de connexion -----------------------------
            if result.login_form_detected and brand and brand not in host:''',
        '''            # --- Usurpation de page de connexion -----------------------------
            # On n'accuse JAMAIS une page de transit (interstitiel de
            # raccourcisseur) : marque et formulaire n'y sont pas fiables.
            if (result.login_form_detected and brand and brand not in host
                    and not interstitial):''',
    ),
    (
        '''            elif result.login_form_detected:
                result.signals.append(Signal(
                    code="password_form", title="Formulaire de mot de passe détecté",''',
        '''            elif result.login_form_detected and not interstitial:
                result.signals.append(Signal(
                    code="password_form", title="Formulaire de mot de passe détecté",''',
    ),
]

for i, (old, new) in enumerate(PAIRS, 1):
    n = s.count(old)
    assert n == 1, f"[ABANDON] ancre #{i} trouvee {n} fois (attendu 1) - rien ecrit"
    s = s.replace(old, new, 1)

ast.parse(s)
open(PATH, "w", encoding="utf-8").write(s)
print("[ok] dynamic_sandbox.py patche : suivi redirections + garde anti-interstitiel")
