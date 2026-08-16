"""
Primitives post-quantiques opérationnelles (PQClean via `pqcrypto`).

Trois usages distincts, chacun justifié — pas de cryptographie décorative :

  - ML-DSA-65 (FIPS 204)  : signature de chaque VERDICT, en hybride avec Ed25519.
                            Chemin chaud : taille (~3,3 Ko) et vitesse acceptables.
  - SLH-DSA-SHA2-128s (FIPS 205) : signature du MANIFESTE DE CLÉS (racine de
                            confiance). Signé rarement, sécurité conservatrice
                            (hachage pur, aucune hypothèse sur réseaux euclidiens).
  - ML-KEM-768 (FIPS 203) : encapsulation pour l'ENVELOPPE DE CONFIDENTIALITÉ
                            hybride des requêtes (résistance « harvest-now-
                            decrypt-later »), en complément du relais OHTTP.

Toutes ces primitives sont réellement exécutées et testées (voir tests/).
"""
from __future__ import annotations

import base64

from pqcrypto.sign import ml_dsa_65 as _mldsa
import slhdsa as _slh  # SLH-DSA (FIPS 205), implementation pure-Python
from pqcrypto.kem import ml_kem_768 as _mlkem

# Noms normatifs exposés au client.
ALG_SIG_PQ = "ML-DSA-65"
ALG_ROOT_PQ = "SLH-DSA-SHA2-128s"
ALG_KEM_PQ = "ML-KEM-768"


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def unb64(data: str) -> bytes:
    return base64.b64decode(data.encode("ascii"))


# --------------------------------------------------------------------------- #
#  ML-DSA-65 — signature de verdict
# --------------------------------------------------------------------------- #
def mldsa_generate():
    """Retourne (public_key, secret_key)."""
    return _mldsa.generate_keypair()


def mldsa_sign(secret_key: bytes, message: bytes) -> bytes:
    return _mldsa.sign(secret_key, message)


def mldsa_verify(public_key: bytes, message: bytes, signature: bytes) -> bool:
    try:
        return _mldsa.verify(public_key, message, signature)
    except Exception:  # noqa: BLE001
        return False


# --------------------------------------------------------------------------- #
#  SLH-DSA (SPHINCS+) — racine de confiance / manifeste
# --------------------------------------------------------------------------- #
_SLH_PARAM = _slh.sha2_128s  # SLH-DSA-SHA2-128s


def slhdsa_generate():
    """Racine SLH-DSA FIPS 205 ; retourne (public_key, secret_key) bruts."""
    kp = _slh.KeyPair.gen(_SLH_PARAM)
    return kp.pub.digest(), kp.sec.digest()


def slhdsa_sign(secret_key: bytes, message: bytes) -> bytes:
    # Signature "pure" FIPS 205 (contexte vide) ; interopere avec le
    # SLHDSASigner de BouncyCastle cote client (verifySignature(M, sig)).
    sk = _slh.SecretKey.from_digest(secret_key, _SLH_PARAM)
    return sk.sign_pure(message)


def slhdsa_verify(public_key: bytes, message: bytes, signature: bytes) -> bool:
    try:
        pk = _slh.PublicKey.from_digest(public_key, _SLH_PARAM)
        return pk.verify_pure(message, signature)
    except Exception:  # noqa: BLE001
        return False


# --------------------------------------------------------------------------- #
#  ML-KEM-768 — encapsulation pour enveloppe de confidentialité
# --------------------------------------------------------------------------- #
def mlkem_generate():
    return _mlkem.generate_keypair()


def mlkem_encapsulate(public_key: bytes):
    """Retourne (ciphertext, shared_secret)."""
    return _mlkem.encrypt(public_key)


def mlkem_decapsulate(secret_key: bytes, ciphertext: bytes) -> bytes:
    return _mlkem.decrypt(secret_key, ciphertext)
