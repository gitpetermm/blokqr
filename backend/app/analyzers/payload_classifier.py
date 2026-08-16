"""
Classification du contenu décodé.

Détermine la nature du payload (URL, Wi-Fi, vCard, deep link, crypto, etc.)
afin d'orienter l'analyse. La distinction URL réseau / deep link est cruciale :
les deep links (schémas applicatifs) peuvent déclencher des actions sensibles
sans confirmation et sont un vecteur de QRLjacking / detournement d'app.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Optional
from urllib.parse import urlsplit

from app.schemas import PayloadType

# Schémas considérés comme deep links applicatifs (non navigables en HTTP).
_DEEP_LINK_SCHEMES = {
    "intent", "android-app", "ios-app", "fb", "whatsapp", "tg", "telegram",
    "spotify", "slack", "zoommtg", "msteams", "market", "itms-apps",
}
_CRYPTO_SCHEMES = {"bitcoin", "ethereum", "litecoin", "monero", "bitcoincash"}


@dataclass
class ClassifiedPayload:
    payload_type: PayloadType
    normalized: str
    url: Optional[str] = None          # URL réseau extraite, le cas échéant
    scheme: Optional[str] = None
    extras: dict = field(default_factory=dict)


def classify(raw: str) -> ClassifiedPayload:
    """Classe une chaîne brute décodée depuis un QR / code-barres."""
    value = raw.strip()
    lowered = value.lower()

    # --- Wi-Fi : WIFI:T:WPA;S:ssid;P:pass;; ---------------------------------
    if lowered.startswith("wifi:"):
        body = value[5:]
        fields = {}
        for token in re.split(r"(?<!\\);", body):
            if ":" in token:
                key, _, val = token.partition(":")
                fields[key.upper()] = val
        return ClassifiedPayload(
            payload_type=PayloadType.WIFI,
            normalized=value,
            extras={
                "ssid": fields.get("S", ""),
                "auth": fields.get("T", ""),
                "hidden": fields.get("H", ""),
                "has_password": bool(fields.get("P")),
            },
        )

    # --- vCard / MeCard -----------------------------------------------------
    if lowered.startswith("begin:vcard"):
        return ClassifiedPayload(PayloadType.VCARD, value)
    if lowered.startswith("mecard:"):
        return ClassifiedPayload(PayloadType.MECARD, value)

    # --- Calendrier ---------------------------------------------------------
    if lowered.startswith("begin:vevent") or lowered.startswith("begin:vcalendar"):
        return ClassifiedPayload(PayloadType.CALENDAR, value)

    # --- Schémas simples ----------------------------------------------------
    if lowered.startswith(("mailto:",)):
        return ClassifiedPayload(PayloadType.EMAIL, value, scheme="mailto")
    if lowered.startswith(("tel:",)):
        return ClassifiedPayload(PayloadType.PHONE, value, scheme="tel")
    if lowered.startswith(("sms:", "smsto:")):
        return ClassifiedPayload(PayloadType.SMS, value, scheme="sms")
    if lowered.startswith("geo:"):
        return ClassifiedPayload(PayloadType.GEO, value, scheme="geo")

    # --- Schéma générique ---------------------------------------------------
    parts = urlsplit(value)
    scheme = (parts.scheme or "").lower()

    if scheme in ("http", "https"):
        return ClassifiedPayload(
            payload_type=PayloadType.URL,
            normalized=value,
            url=value,
            scheme=scheme,
        )

    if scheme in _CRYPTO_SCHEMES:
        return ClassifiedPayload(PayloadType.CRYPTO, value, scheme=scheme)

    if scheme in _DEEP_LINK_SCHEMES or (scheme and parts.netloc == "" and "://" in value):
        return ClassifiedPayload(
            payload_type=PayloadType.DEEP_LINK,
            normalized=value,
            scheme=scheme or None,
        )

    # --- Heuristique « domaine nu » (ex. exemple.com/login) -----------------
    if re.match(r"^[a-z0-9.-]+\.[a-z]{2,}(/.*)?$", lowered) and " " not in value:
        candidate = f"http://{value}"
        return ClassifiedPayload(
            payload_type=PayloadType.URL,
            normalized=value,
            url=candidate,
            scheme="http",
            extras={"implicit_scheme": True},
        )

    return ClassifiedPayload(PayloadType.TEXT, value)
