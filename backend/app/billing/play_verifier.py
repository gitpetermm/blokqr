"""Vérification serveur d'un abonnement via la Google Play Developer API.

Appelle `purchases.subscriptionsv2.get` avec un jeton OAuth2 dérivé du compte de
service (scope androidpublisher), interprète l'état de l'abonnement, et permet
l'acquittement de l'achat (obligatoire sous 3 jours, sinon remboursement).

Aucune identité n'est conservée : on vérifie le `purchaseToken` de façon
transactionnelle (cohérent avec la conception privacy-first de BlokQR).

L'I/O réseau (jeton + appels HTTP) est isolée dans des helpers pour permettre
des tests hermétiques sans réseau ni identifiants.
"""
from __future__ import annotations

import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

import httpx

from app.config import Settings

_SCOPE = "https://www.googleapis.com/auth/androidpublisher"

# ID de l'abonnement (doit correspondre au produit créé en Play Console et à
# BlokQrBilling.PRODUCT_ID côté app).
PRODUCT_ID = "blokqr_pro"

# États de subscriptionState donnant droit à l'accès Pro.
_ACTIVE_STATES = {
    "SUBSCRIPTION_STATE_ACTIVE",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
}
# CANCELED = résiliation programmée mais accès maintenu jusqu'à l'expiration.
_CANCELED_STATE = "SUBSCRIPTION_STATE_CANCELED"
_ACK_DONE = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"


@dataclass
class PlayVerification:
    entitled: bool
    plan: Optional[str]            # basePlanId (pro-monthly / pro-annual)
    expiry_iso: Optional[str]
    state: str
    acknowledged: bool


# --------------------------------------------------------------------------- #
# Authentification (jeton OAuth2 du compte de service, mis en cache)
# --------------------------------------------------------------------------- #
class _CredsCache:
    """Cache thread-safe des identifiants du compte de service."""
    _lock = threading.Lock()
    _creds = None

    @classmethod
    def bearer(cls, settings: Settings) -> str:
        # Imports paresseux : la lib n'est nécessaire que si la facturation est active.
        from google.auth.transport.requests import Request as GoogleAuthRequest
        from google.oauth2 import service_account
        with cls._lock:
            if cls._creds is None:
                cls._creds = service_account.Credentials.from_service_account_file(
                    settings.play_service_account_path, scopes=[_SCOPE])
            if not cls._creds.valid:
                cls._creds.refresh(GoogleAuthRequest())
            return cls._creds.token


# --------------------------------------------------------------------------- #
# I/O réseau isolée (mockable en test)
# --------------------------------------------------------------------------- #
def _get_json(url: str, settings: Settings) -> dict:
    token = _CredsCache.bearer(settings)
    with httpx.Client(timeout=settings.play_api_timeout_seconds) as client:
        resp = client.get(url, headers={"Authorization": f"Bearer {token}"})
    resp.raise_for_status()
    return resp.json()


def _post(url: str, settings: Settings) -> None:
    token = _CredsCache.bearer(settings)
    with httpx.Client(timeout=settings.play_api_timeout_seconds) as client:
        resp = client.post(url, headers={"Authorization": f"Bearer {token}"}, json={})
    resp.raise_for_status()


# --------------------------------------------------------------------------- #
# Logique métier (pure, testable)
# --------------------------------------------------------------------------- #
def _parse_iso(s: Optional[str]) -> Optional[datetime]:
    if not s:
        return None
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None


def _not_expired(expiry_iso: Optional[str]) -> bool:
    dt = _parse_iso(expiry_iso)
    return dt is not None and dt > datetime.now(timezone.utc)


def interpret(data: dict) -> PlayVerification:
    """Transforme une réponse SubscriptionPurchaseV2 en verdict d'accès."""
    state = data.get("subscriptionState", "") or ""
    line_items = data.get("lineItems") or []
    expiries = [li.get("expiryTime") for li in line_items if li.get("expiryTime")]
    expiry_iso = max(expiries) if expiries else None
    plan = None
    for li in line_items:
        bp = (li.get("offerDetails") or {}).get("basePlanId")
        if bp:
            plan = bp
            break
    acknowledged = data.get("acknowledgementState") == _ACK_DONE
    entitled = (
        (state in _ACTIVE_STATES or state == _CANCELED_STATE)
        and _not_expired(expiry_iso)
    )
    return PlayVerification(
        entitled=entitled, plan=plan, expiry_iso=expiry_iso,
        state=state, acknowledged=acknowledged,
    )


def _subscriptions_v2_url(settings: Settings, purchase_token: str) -> str:
    return (f"{settings.play_api_base}/androidpublisher/v3/applications/"
            f"{settings.play_package_name}/purchases/subscriptionsv2/tokens/"
            f"{purchase_token}")


def _acknowledge_url(settings: Settings, purchase_token: str) -> str:
    return (f"{settings.play_api_base}/androidpublisher/v3/applications/"
            f"{settings.play_package_name}/purchases/subscriptions/"
            f"{PRODUCT_ID}/tokens/{purchase_token}:acknowledge")


# --------------------------------------------------------------------------- #
# API publique (appelée par la route, via asyncio.to_thread car bloquante)
# --------------------------------------------------------------------------- #
def verify_subscription(purchase_token: str, settings: Settings) -> PlayVerification:
    """Vérifie l'abonnement auprès de Google et renvoie le verdict d'accès."""
    data = _get_json(_subscriptions_v2_url(settings, purchase_token), settings)
    return interpret(data)


def acknowledge_subscription(purchase_token: str, settings: Settings) -> None:
    """Acquitte l'achat (à faire une fois l'accès accordé)."""
    _post(_acknowledge_url(settings, purchase_token), settings)
