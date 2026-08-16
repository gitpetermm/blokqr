"""
Analyse lexicale et statique des URL.

Détecte sans aucune connexion réseau les signaux structurels de malveillance :
  - attaques homographes / IDN (caractères Unicode trompeurs),
  - sous-domaines de marque trompeurs (paypal.secure-login.tld),
  - TLD à risque, IP littérale, ports inhabituels, identifiants embarqués (@),
  - raccourcisseurs d'URL,
  - entropie élevée (domaines générés algorithmiquement - DGA),
  - points d'entrée de session « login with QR » (vecteur QRLjacking).

Ces signaux sont peu coûteux, fonctionnent hors-ligne et servent de premier
filtre avant les analyses réseau plus lourdes.
"""
from __future__ import annotations

import math
import re
from typing import List
from urllib.parse import parse_qs, urlsplit

from app.schemas import Severity, Signal

_SOURCE = "lexical"

# Raccourcisseurs courants : la cible réelle est masquée -> résolution requise.
URL_SHORTENERS = {
    "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly",
    "cutt.ly", "rebrand.ly", "shorturl.at", "rb.gy", "lnkd.in", "tiny.cc",
    "t.ly", "s.id", "v.gd", "qr.codes", "linktr.ee",
}

# TLD statistiquement très abusés par le phishing.
HIGH_RISK_TLDS = {
    "zip", "mov", "xyz", "top", "click", "link", "gq", "cf", "ml", "tk",
    "ga", "rest", "country", "kim", "work", "fit", "loan", "men", "cam",
    "sbs", "cfd", "icu", "support",
}

# Mots-clés d'hameçonnage fréquents dans le chemin / hôte.
PHISHING_KEYWORDS = {
    "login", "signin", "verify", "secure", "account", "update", "confirm",
    "webscr", "wallet", "unlock", "validation", "authentication", "billing",
    "suspended", "recover", "appeal", "connexion", "verification", "paiement",
    "facture", "impot", "ameli", "colis", "livraison",
}

# Marques fréquemment usurpées (pour détecter le « combosquatting »).
TARGET_BRANDS = {
    "paypal", "microsoft", "office365", "apple", "icloud", "amazon", "google",
    "netflix", "dhl", "fedex", "ups", "laposte", "chronopost", "ameli",
    "impots", "orange", "free", "sfr", "bnp", "creditagricole", "societegenerale",
    "whatsapp", "instagram", "facebook", "linkedin", "binance", "coinbase",
}

# Domaines de service « login with QR » -> surface QRLjacking.
QR_LOGIN_HOSTS = {
    "web.whatsapp.com", "web.telegram.org", "web.wechat.com", "discord.com",
    "steamcommunity.com", "accounts.binance.com", "passport.weibo.com",
}


def _shannon_entropy(s: str) -> float:
    if not s:
        return 0.0
    freq = {c: s.count(c) for c in set(s)}
    length = len(s)
    return -sum((n / length) * math.log2(n / length) for n in freq.values())


def _registrable_label(host: str) -> str:
    """Renvoie le label de domaine sans le TLD (heuristique simple)."""
    parts = host.split(".")
    return parts[-2] if len(parts) >= 2 else host


def analyze_url(url: str) -> List[Signal]:
    """Retourne la liste des signaux lexicaux détectés sur une URL."""
    signals: List[Signal] = []
    parts = urlsplit(url)
    host = (parts.hostname or "").lower()
    path = parts.path or ""
    full = url.lower()

    if not host:
        return signals

    # --- 1. Identifiants embarqués (user@host) ------------------------------
    if "@" in parts.netloc:
        signals.append(Signal(
            code="embedded_credentials",
            title="Identifiants embarqués dans l'URL",
            detail="Présence d'un '@' dans l'autorité : technique de masquage du vrai hôte.",
            severity=Severity.HIGH, weight=25, source=_SOURCE,
        ))

    # --- 2. IP littérale en hôte --------------------------------------------
    if re.match(r"^\d{1,3}(\.\d{1,3}){3}$", host) or ":" in host:
        signals.append(Signal(
            code="ip_literal_host",
            title="Hôte sous forme d'adresse IP",
            detail="Les sites légitimes utilisent rarement une IP brute.",
            severity=Severity.MEDIUM, weight=18, source=_SOURCE,
        ))

    # --- 3. IDN / homographe ------------------------------------------------
    if host.startswith("xn--") or "xn--" in host:
        signals.append(Signal(
            code="punycode_idn",
            title="Domaine internationalisé (punycode)",
            detail="Risque d'attaque homographe imitant un domaine légitime.",
            severity=Severity.HIGH, weight=22, source=_SOURCE,
        ))
    if any(ord(c) > 127 for c in host):
        signals.append(Signal(
            code="non_ascii_host",
            title="Caractères non-ASCII dans l'hôte",
            detail="Caractères Unicode pouvant imiter visuellement des lettres latines.",
            severity=Severity.HIGH, weight=20, source=_SOURCE,
        ))

    # --- 4. TLD à risque ----------------------------------------------------
    tld = host.rsplit(".", 1)[-1] if "." in host else ""
    if tld in HIGH_RISK_TLDS:
        signals.append(Signal(
            code="high_risk_tld",
            title=f"Extension à risque (.{tld})",
            detail="TLD statistiquement très utilisé pour l'hameçonnage.",
            severity=Severity.MEDIUM, weight=12, source=_SOURCE,
        ))

    # --- 5. Raccourcisseur d'URL --------------------------------------------
    if host in URL_SHORTENERS:
        signals.append(Signal(
            code="url_shortener",
            title="Raccourcisseur d'URL",
            detail="La destination réelle est masquée et sera résolue par analyse des redirections.",
            severity=Severity.LOW, weight=8, source=_SOURCE,
        ))

    # --- 6. Combosquatting de marque ----------------------------------------
    label = _registrable_label(host)
    for brand in TARGET_BRANDS:
        if brand in host and label != brand and not host.endswith(f"{brand}.com"):
            signals.append(Signal(
                code="brand_combosquatting",
                title=f"Marque « {brand} » détournée dans le domaine",
                detail=f"'{brand}' apparaît dans un domaine tiers ({host}) : usurpation probable.",
                severity=Severity.HIGH, weight=24, source=_SOURCE,
            ))
            break

    # --- 7. Profusion de sous-domaines --------------------------------------
    subdomain_count = host.count(".")
    if subdomain_count >= 4:
        signals.append(Signal(
            code="excessive_subdomains",
            title="Nombre élevé de sous-domaines",
            detail="Empilement de sous-domaines souvent utilisé pour noyer le vrai domaine.",
            severity=Severity.LOW, weight=8, source=_SOURCE,
        ))

    # --- 8. Mots-clés d'hameçonnage -----------------------------------------
    hits = sorted({kw for kw in PHISHING_KEYWORDS if kw in full})
    if hits:
        severity = Severity.MEDIUM if len(hits) >= 2 else Severity.LOW
        signals.append(Signal(
            code="phishing_keywords",
            title="Mots-clés sensibles dans l'URL",
            detail=f"Termes détectés : {', '.join(hits[:6])}.",
            severity=severity, weight=6 * min(len(hits), 3), source=_SOURCE,
        ))

    # --- 9. Entropie élevée du domaine (DGA) --------------------------------
    entropy = _shannon_entropy(label)
    if len(label) >= 10 and entropy >= 3.6:
        signals.append(Signal(
            code="high_entropy_domain",
            title="Domaine à forte entropie",
            detail=f"Entropie {entropy:.2f} : domaine possiblement généré algorithmiquement.",
            severity=Severity.MEDIUM, weight=12, source=_SOURCE,
        ))

    # --- 10. Port inhabituel ------------------------------------------------
    if parts.port and parts.port not in (80, 443):
        signals.append(Signal(
            code="unusual_port",
            title=f"Port inhabituel ({parts.port})",
            detail="Les services web légitimes utilisent généralement 80/443.",
            severity=Severity.LOW, weight=8, source=_SOURCE,
        ))

    # --- 11. Surface QRLjacking ---------------------------------------------
    if host in QR_LOGIN_HOSTS or re.search(r"/(qr|qrcode|qrlogin|web)?login", path):
        signals.append(Signal(
            code="qr_login_endpoint",
            title="Point d'entrée d'authentification par QR",
            detail=(
                "Ce code semble initier une connexion « Login with QR ». "
                "Le scanner pourrait autoriser une session contrôlée par un tiers "
                "(attaque QRLjacking). Ne scannez que des QR de connexion affichés "
                "par vos propres appareils de confiance."
            ),
            severity=Severity.HIGH, weight=20, source=_SOURCE,
        ))

    # --- 12. Fichiers exécutables / archives en cible -----------------------
    if re.search(r"\.(apk|exe|msi|scr|bat|cmd|jar|dmg|zip|mov)(\?|$)", full):
        signals.append(Signal(
            code="executable_target",
            title="Cible de type exécutable/archive",
            detail="Le lien pointe vers un binaire téléchargeable : risque de malware.",
            severity=Severity.HIGH, weight=22, source=_SOURCE,
        ))

    # --- 13. Paramètres de redirection ouverte ------------------------------
    qs = parse_qs(parts.query)
    redirect_params = {"url", "redirect", "next", "u", "r", "dest", "return", "continue"}
    for p in redirect_params & set(qs.keys()):
        val = qs[p][0] if qs[p] else ""
        if val.startswith(("http", "//", "%2F%2F", "https%3A")):
            signals.append(Signal(
                code="open_redirect_param",
                title="Paramètre de redirection ouverte",
                detail=f"Le paramètre '{p}' transporte une URL : possible open redirect.",
                severity=Severity.MEDIUM, weight=14, source=_SOURCE,
            ))
            break

    return signals
