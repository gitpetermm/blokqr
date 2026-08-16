#!/usr/bin/env python3
"""
Initialise TOUT le matériel cryptographique de BlokQR (v4).

À exécuter UNE FOIS au déploiement, depuis le dossier backend/ :

    python scripts/generate_keys.py

Produit :
  1. VERDICT_SIGNING_SEED_B64        -> à placer dans .env (clé Ed25519 stable).
  2. keys/slhdsa_root.json           -> racine de confiance SLH-DSA persistée.
  3. PINNED_SLHDSA_ROOT_PUBKEY_B64   -> à épingler dans l'app Android (Config.kt).
  4. keys/gateway_keys.json          -> paire d'enveloppe ML-KEM-768 + X25519 persistée.

La racine SLH-DSA est l'unique élément épinglé côté client : les clés de verdict
(Ed25519 + ML-DSA-65), elles, sont éphémères et publiées via /manifest signé.
La paire de passerelle, elle, DOIT être persistée (et identique entre workers) :
le client scelle ses requêtes vers sa clé publique, exposée sur /pq-pubkey.

En production, conserver keys/ hors du conteneur (volume monté), permissions
restreintes, et idéalement les clés privées en HSM/KMS. Ne JAMAIS committer keys/.
"""
import base64
import os
import sys

# Permet l'exécution depuis backend/ sans installation.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from app.security.key_manifest import ManifestSigner
from app.security.pq_envelope import GatewayKeys


def main() -> None:
    # 1. Seed Ed25519 stable.
    priv = Ed25519PrivateKey.generate()
    seed_b64 = base64.b64encode(priv.private_bytes_raw()).decode()

    # 2. Racine SLH-DSA (créée et persistée si absente).
    root_path = os.environ.get("SPHINCS_ROOT_KEY_PATH", "keys/slhdsa_root.json")
    signer = ManifestSigner(root_path)
    root_pub = signer.root_pub_b64

    # 3. Paire de passerelle (enveloppe ML-KEM-768 + X25519), persistée si absente.
    gw_path = os.environ.get("GATEWAY_KEY_PATH", "keys/gateway_keys.json")
    gw = GatewayKeys.load_or_create(gw_path)
    gw_bundle = gw.public_bundle

    print("# ----- À copier dans backend/.env -----")
    print("VERDICT_SIGNING_SEED_B64=" + seed_b64)
    print(f"SPHINCS_ROOT_KEY_PATH={root_path}")
    print(f"GATEWAY_KEY_PATH={gw_path}")
    print()
    print("# ----- À épingler dans android/.../Config.kt -----")
    print("PINNED_SLHDSA_ROOT_PUBKEY_B64=" + root_pub)
    print()
    print(f"# Racine SLH-DSA persistée : {root_path}")
    print(f"# Paire de passerelle persistée : {gw_path}  (alg={gw_bundle['alg']})")
    print("#   clé publique ML-KEM-768 (extrait) : " + gw_bundle["mlkem768_pub"][:24] + "…")
    print("#   clé publique X25519              : " + gw_bundle["x25519_pub"])
    print("# (sauvegarder keys/ hors conteneur ; ne JAMAIS committer)")


if __name__ == "__main__":
    main()
