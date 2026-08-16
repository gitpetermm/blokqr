"""
Racine de confiance et manifeste de clés (signé SLH-DSA / SPHINCS+).

Problème : les clés de verdict (Ed25519, ML-DSA-65) et la clé ML-KEM sont
éphémères et doivent pouvoir tourner sans recompiler le client. On ne peut donc
pas les épingler directement dans l'application.

Solution : une clé racine SLH-DSA, STABLE et conservatrice (idéalement en HSM/
KMS), signe un « manifeste » qui publie les clés courantes. Le client n'épingle
que la clé publique racine SLH-DSA ; il récupère le manifeste signé, le vérifie,
puis fait confiance aux clés qu'il contient. La rotation devient une simple
réémission de manifeste.

La clé racine est persistée sur disque (chemin de configuration). En production,
la remplacer par une clé non exportable en HSM/KMS.
"""
from __future__ import annotations

import json
import os
import threading
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Optional

from app.security import pqc


def _canonical_manifest(fields: dict) -> str:
    return json.dumps(fields, separators=(",", ":"), sort_keys=True, ensure_ascii=False)


@dataclass
class KeyManifest:
    version: int
    key_id: str
    ed25519_pub_b64: str
    mldsa65_pub_b64: str
    mlkem768_pub_b64: str
    issued_at: str
    not_after: str
    canonical: str
    root_alg: str
    sig_slhdsa_b64: str
    root_pub_b64: str


class ManifestSigner:
    """Détient la racine SLH-DSA et produit des manifestes signés."""

    def __init__(self, root_key_path: str) -> None:
        self._path = root_key_path
        self._pub, self._sk = self._load_or_create()
        # Cache du manifeste signé : la signature SLH-DSA (pure-Python) coûte
        # ~1-2 s. On ne re-signe que si les clés publiées changent (rotation)
        # ou à cadence fixe. Évite un DoS CPU sur /manifest et le blocage de
        # l'event-loop async.
        self._lock = threading.Lock()
        self._cache: Optional["KeyManifest"] = None
        self._cache_key: Optional[tuple] = None

    def _load_or_create(self):
        if self._path and os.path.exists(self._path):
            with open(self._path, "r", encoding="utf-8") as f:
                data = json.load(f)
            return pqc.unb64(data["pub"]), pqc.unb64(data["sk"])
        pub, sk = pqc.slhdsa_generate()
        if self._path:
            os.makedirs(os.path.dirname(self._path) or ".", exist_ok=True)
            with open(self._path, "w", encoding="utf-8") as f:
                json.dump({"pub": pqc.b64(pub), "sk": pqc.b64(sk)}, f)
            try:
                os.chmod(self._path, 0o600)
            except OSError:
                pass
        return pub, sk

    @property
    def root_pub_b64(self) -> str:
        return pqc.b64(self._pub)

    def build(self, *, version: int, key_id: str, ed25519_pub_b64: str,
              mldsa65_pub_b64: str, mlkem768_pub_b64: str,
              validity_days: int = 30) -> KeyManifest:
        now = datetime.now(timezone.utc)
        fields = {
            "version": version,
            "key_id": key_id,
            "ed25519_pub": ed25519_pub_b64,
            "mldsa65_pub": mldsa65_pub_b64,
            "mlkem768_pub": mlkem768_pub_b64,
            "issued_at": now.isoformat(),
            "not_after": (now + timedelta(days=validity_days)).isoformat(),
            "root_alg": pqc.ALG_ROOT_PQ,
        }
        canonical = _canonical_manifest(fields)
        sig = pqc.slhdsa_sign(self._sk, canonical.encode("utf-8"))
        return KeyManifest(
            version=version, key_id=key_id,
            ed25519_pub_b64=ed25519_pub_b64, mldsa65_pub_b64=mldsa65_pub_b64,
            mlkem768_pub_b64=mlkem768_pub_b64,
            issued_at=fields["issued_at"], not_after=fields["not_after"],
            canonical=canonical, root_alg=pqc.ALG_ROOT_PQ,
            sig_slhdsa_b64=pqc.b64(sig), root_pub_b64=self.root_pub_b64,
        )

    def get_or_build(self, *, version: int, key_id: str, ed25519_pub_b64: str,
                     mldsa65_pub_b64: str, mlkem768_pub_b64: str,
                     validity_days: int = 2,
                     refresh_interval_seconds: int = 21600) -> KeyManifest:
        """Renvoie le manifeste signé en cache, ne re-signant qu'à cadence fixe.

        Re-signe uniquement si : aucun cache, les clés publiées ont changé
        (rotation), ou le dernier manifeste date de plus de
        refresh_interval_seconds (6 h par défaut, aligné sur le cache client de
        6 h). La signature SLH-DSA n'est donc jamais exécutée par requête
        (pas de DoS CPU) et issued_at reste frais (<= 6 h). Validité par
        défaut : 48 h -> fenêtre de rejeu courte.
        """
        cache_key = (version, key_id, ed25519_pub_b64, mldsa65_pub_b64,
                     mlkem768_pub_b64, validity_days)
        now = datetime.now(timezone.utc)
        with self._lock:
            cached = self._cache
            if cached is not None and self._cache_key == cache_key:
                issued_at = datetime.fromisoformat(cached.issued_at)
                if (now - issued_at).total_seconds() < refresh_interval_seconds:
                    return cached
            m = self.build(
                version=version, key_id=key_id,
                ed25519_pub_b64=ed25519_pub_b64,
                mldsa65_pub_b64=mldsa65_pub_b64,
                mlkem768_pub_b64=mlkem768_pub_b64,
                validity_days=validity_days,
            )
            self._cache = m
            self._cache_key = cache_key
            return m


def verify_manifest(canonical: str, sig_slhdsa_b64: str,
                    pinned_root_pub_b64: str) -> bool:
    """Vérifie le manifeste contre la racine SLH-DSA épinglée (réplique client)."""
    return pqc.slhdsa_verify(
        pqc.unb64(pinned_root_pub_b64),
        canonical.encode("utf-8"),
        pqc.unb64(sig_slhdsa_b64),
    )
