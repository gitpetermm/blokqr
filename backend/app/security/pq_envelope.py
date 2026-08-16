"""
Enveloppe de confidentialité hybride (X25519 + ML-KEM-768).

Répond à la question « où est ML-KEM ? » par un usage NON décoratif : chiffrer
le corps des requêtes (analyse, réputation) de bout en bout vers la passerelle,
indépendamment de TLS et en complément du relais OHTTP. L'hybridation associe la
robustesse éprouvée de X25519 et la résistance quantique de ML-KEM-768, de sorte
qu'un adversaire « harvest-now, decrypt-later » devrait casser les deux.

Schéma (proche de HPKE) :
  1. le client encapsule vers la clé publique hybride de la passerelle :
       - ML-KEM-768 -> (ct_pq, ss_pq)
       - X25519     -> (epk, ss_cl)  via une clé éphémère client
  2. clé symétrique = HKDF-SHA256(ss_pq || ss_cl)
  3. AEAD = ChaCha20-Poly1305(clé) sur le corps applicatif.

La passerelle décapsule avec ses deux clés privées et ouvre l'AEAD.

Persistance : les clés PRIVÉES de la passerelle sont générées une seule fois et
persistées (keys/gateway_keys.json), afin d'être partagées par tous les workers
et conservées entre redémarrages. Sinon, une clé éphémère par worker/redémarrage
casserait la décapsulation (le client scelle vers une clé qui n'existe plus).
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass

from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey, X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

from app.security import pqc

_INFO = b"blokqr/pq-envelope/v1"


def _derive_key(ss_pq: bytes, ss_classical: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=None, info=_INFO).derive(
        ss_pq + ss_classical
    )


@dataclass
class GatewayKeys:
    """Clés privées de la passerelle (hybride)."""

    mlkem_pub: bytes
    mlkem_sk: bytes
    x25519_priv: X25519PrivateKey

    @classmethod
    def generate(cls) -> "GatewayKeys":
        mlkem_pub, mlkem_sk = pqc.mlkem_generate()
        return cls(mlkem_pub, mlkem_sk, X25519PrivateKey.generate())

    @property
    def public_bundle(self) -> dict:
        return {
            "mlkem768_pub": pqc.b64(self.mlkem_pub),
            "x25519_pub": pqc.b64(self.x25519_priv.public_key().public_bytes_raw()),
            "alg": f"{pqc.ALG_KEM_PQ}+X25519",
        }

    # ----------------------------- Persistance ----------------------------- #

    def save(self, path: str) -> None:
        """Écrit la paire (privée incluse) en JSON base64, permissions 0600.

        Écriture atomique (fichier temporaire + os.replace) pour éviter qu'un
        worker lise un fichier à moitié écrit.
        """
        directory = os.path.dirname(path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        payload = {
            "alg": f"{pqc.ALG_KEM_PQ}+X25519",
            "mlkem_pub": pqc.b64(self.mlkem_pub),
            "mlkem_sk": pqc.b64(self.mlkem_sk),
            "x25519_priv": pqc.b64(self.x25519_priv.private_bytes_raw()),
        }
        tmp = f"{path}.tmp.{os.getpid()}"
        # Création directe en 0600 (umask-safe) puis écriture.
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(payload, fh, separators=(",", ":"))
        except BaseException:
            try:
                os.unlink(tmp)
            finally:
                raise
        os.replace(tmp, path)
        os.chmod(path, 0o600)

    @classmethod
    def load(cls, path: str) -> "GatewayKeys":
        """Charge une paire persistée."""
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return cls(
            mlkem_pub=pqc.unb64(data["mlkem_pub"]),
            mlkem_sk=pqc.unb64(data["mlkem_sk"]),
            x25519_priv=X25519PrivateKey.from_private_bytes(pqc.unb64(data["x25519_priv"])),
        )

    @classmethod
    def load_or_create(cls, path: str) -> "GatewayKeys":
        """Charge la paire si elle existe, sinon la génère et la persiste.

        En production, exécuter scripts/generate_keys.py au déploiement crée le
        fichier AVANT le démarrage de l'app : tous les workers chargent alors la
        MÊME paire (pas de course à la première initialisation).
        """
        if os.path.exists(path):
            return cls.load(path)
        keys = cls.generate()
        keys.save(path)
        return keys


def seal(plaintext: bytes, gateway_pub: dict) -> dict:
    """Chiffre `plaintext` vers la passerelle. Côté client."""
    mlkem_pub = pqc.unb64(gateway_pub["mlkem768_pub"])
    gw_x = X25519PublicKey.from_public_bytes(pqc.unb64(gateway_pub["x25519_pub"]))

    ct_pq, ss_pq = pqc.mlkem_encapsulate(mlkem_pub)
    eph = X25519PrivateKey.generate()
    ss_cl = eph.exchange(gw_x)
    key = _derive_key(ss_pq, ss_cl)

    aead = ChaCha20Poly1305(key)
    nonce = bytes(12)  # nonce fixe acceptable : clé unique par enveloppe
    ciphertext = aead.encrypt(nonce, plaintext, None)
    return {
        "ct_mlkem": pqc.b64(ct_pq),
        "epk_x25519": pqc.b64(eph.public_key().public_bytes_raw()),
        "ct": pqc.b64(ciphertext),
    }


def open_envelope(envelope: dict, keys: GatewayKeys) -> bytes:
    """Déchiffre une enveloppe. Côté passerelle."""
    ss_pq = pqc.mlkem_decapsulate(keys.mlkem_sk, pqc.unb64(envelope["ct_mlkem"]))
    epk = X25519PublicKey.from_public_bytes(pqc.unb64(envelope["epk_x25519"]))
    ss_cl = keys.x25519_priv.exchange(epk)
    key = _derive_key(ss_pq, ss_cl)
    aead = ChaCha20Poly1305(key)
    return aead.decrypt(bytes(12), pqc.unb64(envelope["ct"]), None)


def seal_json(obj: dict, gateway_pub: dict) -> dict:
    return seal(json.dumps(obj, separators=(",", ":")).encode("utf-8"), gateway_pub)


def open_json(envelope: dict, keys: GatewayKeys) -> dict:
    return json.loads(open_envelope(envelope, keys).decode("utf-8"))
