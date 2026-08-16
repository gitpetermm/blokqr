"""Jeton d'entitlement Pro signé (Ed25519 + ML-DSA).

Le backend émet ce jeton après vérification d'un achat (voir /v1/billing/verify
au Module 3), le client le met en cache et le présente sur /v1/analyze/deep.
Il est vérifié côté serveur (même clé) et côté client (clés publiées par le
manifeste, exactement comme un verdict). Modèle « bearer » sans identité :
cohérent avec la conception privacy-first de BlokQR (aucun compte utilisateur).
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from typing import Optional

from app.security.signing import VerdictSigner, verify_ed25519, verify_mldsa

TYP = "entitlement"
# Champs signés (ordre indifférent : la canonisation trie les clés).
_PAYLOAD_FIELDS = ("exp", "iat", "kid", "nonce", "plan", "pro", "typ")


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(dt: datetime) -> str:
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _parse_iso(s: str) -> Optional[datetime]:
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return None


def _canonical(payload: dict) -> str:
    """Représentation canonique déterministe SIGNÉE (clés triées, compact).

    Le client DOIT reproduire EXACTEMENT ce format pour vérifier la signature :
    JSON UTF-8, clés triées, séparateurs compacts (",",":"), booléen JSON
    true/false. Champs inclus : exp, iat, kid, nonce, plan, pro, typ.
    """
    obj = {k: payload[k] for k in _PAYLOAD_FIELDS}
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def sign_entitlement(signer: VerdictSigner, *, pro: bool, plan: str,
                     ttl_seconds: int, nonce: str) -> str:
    """Émet un jeton d'entitlement signé (JSON compact, prêt à mettre en cache)."""
    now = _now()
    payload = {
        "typ": TYP,
        "pro": bool(pro),
        "plan": plan,
        "iat": _iso(now),
        "exp": _iso(now + timedelta(seconds=int(ttl_seconds))),
        "kid": signer.key_id,
        "nonce": nonce,
    }
    bundle = signer.sign(_canonical(payload))
    token = dict(payload)
    token["sig_ed25519"] = bundle.ed25519_b64
    token["sig_mldsa65"] = bundle.mldsa65_b64
    return json.dumps(token, separators=(",", ":"), ensure_ascii=False)


def verify_entitlement(token: str, signer: VerdictSigner) -> Optional[dict]:
    """Vérifie un jeton (signature + fraîcheur + clé). Renvoie {pro,plan,exp} ou None.

    Refuse si : JSON invalide, type incorrect, champ manquant, kid différent de
    la clé courante, signature Ed25519 (ou ML-DSA si présente) invalide, ou
    jeton expiré. Renvoie None dans tous ces cas (fail-closed).
    """
    if not token:
        return None
    try:
        data = json.loads(token)
    except (ValueError, TypeError):
        return None
    if not isinstance(data, dict) or data.get("typ") != TYP:
        return None
    if any(f not in data for f in _PAYLOAD_FIELDS):
        return None
    if data.get("kid") != signer.key_id:
        return None
    canonical = _canonical(data)
    if not verify_ed25519(canonical, data.get("sig_ed25519", ""),
                          signer.public_key_ed25519_b64):
        return None
    # ML-DSA : vérifié s'il est présent et que la clé PQ existe.
    if signer.pq_enabled and data.get("sig_mldsa65"):
        if not verify_mldsa(canonical, data["sig_mldsa65"],
                            signer.public_key_mldsa65_b64):
            return None
    exp = _parse_iso(data.get("exp", ""))
    if exp is None or exp <= _now():
        return None
    return {"pro": bool(data.get("pro")), "plan": data.get("plan"),
            "exp": data.get("exp")}
