"""
Signature hybride des verdicts : Ed25519 + ML-DSA-65 (FIPS 204).

Un attaquant en position d'homme du milieu pourrait transformer un verdict
« malicious » en « safe ». TLS seul ne suffit pas (proxy d'entreprise hostile,
AC compromise). Chaque verdict est donc signé, et le client vérifie les DEUX
signatures : casser le verdict exige de casser à la fois Ed25519 (classique) et
ML-DSA-65 (post-quantique).

Les clés de verdict sont éphémères par instance : le client ne les épingle pas
directement. Il épingle la racine SLH-DSA (voir key_manifest.py) et fait
confiance aux clés courantes publiées dans un manifeste signé. Cela permet la
rotation sans recompiler le client.
"""
from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass

from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

from app.security import pqc


@dataclass
class SignatureBundle:
    ed25519_b64: str
    mldsa65_b64: str
    public_key_ed25519_b64: str
    public_key_mldsa65_b64: str
    key_id: str


class VerdictSigner:
    """Détient le matériel de signature de verdict (Ed25519 + ML-DSA-65)."""

    def __init__(
        self,
        ed25519_seed_b64: str = "",
        enable_pq: bool = True,
        key_path: str = "",
    ) -> None:
        self._pq_enabled = enable_pq

        # Persistance : on tente de charger un trousseau de verdict existant pour
        # que le key_id reste STABLE entre redémarrages (sinon le manifeste mis en
        # cache par le client ne correspond plus après un reboot du serveur).
        loaded = False
        if key_path and os.path.exists(key_path):
            try:
                with open(key_path, "r", encoding="utf-8") as fh:
                    data = json.load(fh)
                self._priv = Ed25519PrivateKey.from_private_bytes(
                    pqc.unb64(data["ed25519_seed"])
                )
                if enable_pq:
                    self._mldsa_pub = pqc.unb64(data["mldsa_pub"])
                    self._mldsa_sk = pqc.unb64(data["mldsa_sk"])
                else:
                    self._mldsa_pub, self._mldsa_sk = b"", b""
                loaded = True
            except Exception:
                loaded = False  # fichier corrompu -> on régénère proprement

        if not loaded:
            if not ed25519_seed_b64:
                self._priv = Ed25519PrivateKey.generate()
            else:
                seed = pqc.unb64(ed25519_seed_b64)
                if len(seed) != 32:
                    raise ValueError("La seed Ed25519 doit faire 32 octets.")
                self._priv = Ed25519PrivateKey.from_private_bytes(seed)
            if enable_pq:
                self._mldsa_pub, self._mldsa_sk = pqc.mldsa_generate()
            else:
                self._mldsa_pub, self._mldsa_sk = b"", b""
            # Sauvegarde du trousseau (best-effort) pour les démarrages suivants.
            if key_path:
                try:
                    parent = os.path.dirname(key_path)
                    if parent:
                        os.makedirs(parent, exist_ok=True)
                    payload = {
                        "ed25519_seed": pqc.b64(self._priv.private_bytes_raw()),
                        "mldsa_pub": pqc.b64(self._mldsa_pub),
                        "mldsa_sk": pqc.b64(self._mldsa_sk),
                    }
                    with open(key_path, "w", encoding="utf-8") as fh:
                        json.dump(payload, fh)
                    try:
                        os.chmod(key_path, 0o600)
                    except OSError:
                        pass
                except Exception:
                    # Montage en lecture seule ou disque indisponible : le service
                    # fonctionne quand même, mais le key_id ne sera pas stable.
                    pass

        self._pub: Ed25519PublicKey = self._priv.public_key()
        self._pub_raw = self._pub.public_bytes_raw()

        # key_id lie les deux clés publiques (Ed25519 || ML-DSA).
        self.key_id = hashlib.sha256(self._pub_raw + self._mldsa_pub).hexdigest()[:16]

    @property
    def pq_enabled(self) -> bool:
        return self._pq_enabled

    @property
    def public_key_ed25519_b64(self) -> str:
        return pqc.b64(self._pub_raw)

    @property
    def public_key_mldsa65_b64(self) -> str:
        return pqc.b64(self._mldsa_pub) if self._mldsa_pub else ""

    def sign(self, canonical_payload: str) -> SignatureBundle:
        message = canonical_payload.encode("utf-8")
        ed_sig = self._priv.sign(message)
        pq_sig_b64 = ""
        if self._pq_enabled:
            pq_sig_b64 = pqc.b64(pqc.mldsa_sign(self._mldsa_sk, message))
        return SignatureBundle(
            ed25519_b64=pqc.b64(ed_sig),
            mldsa65_b64=pq_sig_b64,
            public_key_ed25519_b64=self.public_key_ed25519_b64,
            public_key_mldsa65_b64=self.public_key_mldsa65_b64,
            key_id=self.key_id,
        )


# --- Utilitaires de vérification (répliqués côté client) ------------------- #
def verify_ed25519(canonical_payload: str, signature_b64: str, public_key_b64: str) -> bool:
    try:
        pub = Ed25519PublicKey.from_public_bytes(pqc.unb64(public_key_b64))
        pub.verify(pqc.unb64(signature_b64), canonical_payload.encode("utf-8"))
        return True
    except Exception:  # noqa: BLE001
        return False


def verify_mldsa(canonical_payload: str, signature_b64: str, public_key_b64: str) -> bool:
    if not signature_b64 or not public_key_b64:
        return False
    return pqc.mldsa_verify(
        pqc.unb64(public_key_b64),
        canonical_payload.encode("utf-8"),
        pqc.unb64(signature_b64),
    )


def verify_hybrid(canonical_payload: str, ed_sig_b64: str, ed_pub_b64: str,
                  pq_sig_b64: str, pq_pub_b64: str) -> bool:
    """Exige la validité des DEUX signatures."""
    return (verify_ed25519(canonical_payload, ed_sig_b64, ed_pub_b64)
            and verify_mldsa(canonical_payload, pq_sig_b64, pq_pub_b64))
