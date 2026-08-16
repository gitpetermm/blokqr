#!/usr/bin/env python3
"""
B3 — Test de bout en bout de l'enveloppe ML-KEM-768 + X25519.

À lancer DANS le conteneur (les dépendances y sont) :

    sudo docker compose exec blokqr python scripts/test_envelope.py

Il interroge l'API locale (127.0.0.1:8000), récupère la clé publique de
passerelle via /pq-pubkey, scelle une vraie requête d'analyse, l'envoie, et
vérifie que le serveur la DÉCHIFFRE (le verdict renvoie le client_nonce d'origine).
Teste aussi la rétro-compat (clair), l'altération (-> 400) et le 402 Pro.
"""
import base64
import json
import sys
import urllib.error
import urllib.request

from app.security.pq_envelope import seal_json

BASE = "http://127.0.0.1:8000"
NONCE = "b3-nonce-0123456789"
MSG = {"raw_payload": "https://example.com/", "symbology": "QR", "client_nonce": NONCE}


def _get(path: str):
    with urllib.request.urlopen(BASE + path, timeout=15) as r:
        return r.status, json.loads(r.read())


def _post(path: str, obj: dict):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(obj).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        body = e.read() or b"{}"
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body.decode(errors="replace")}


def main() -> None:
    ok = True

    st, pub = _get("/pq-pubkey")
    print(f"[pubkey]    {st}  alg={pub.get('alg')}")
    if pub.get("alg") != "ML-KEM-768+X25519":
        print("  !! Passerelle désactivée ou clé absente — vérifier B1.")
        sys.exit(1)

    # 1) Corps EN CLAIR (rétro-compatibilité)
    st, v = _post("/v1/analyze", MSG)
    sig = next((k for k in v if isinstance(v, dict) and k.startswith("sig")), None)
    p1 = st == 200 and v.get("client_nonce") == NONCE
    print(f"[clair]     {st}  verdict={v.get('verdict')}  nonce_ok={v.get('client_nonce') == NONCE}  sig={sig}")
    ok &= p1

    # 2) Corps ENVELOPPÉ -> le serveur doit déchiffrer et renvoyer NOTRE nonce
    env = seal_json(MSG, pub)
    st, v = _post("/v1/analyze", env)
    p2 = st == 200 and v.get("client_nonce") == NONCE
    print(f"[enveloppe] {st}  verdict={v.get('verdict')}  nonce_ok={v.get('client_nonce') == NONCE}")
    ok &= p2

    # 3) Enveloppe ALTÉRÉE -> 400 pq_envelope_decrypt_failed
    bad = dict(env)
    ct = bytearray(base64.b64decode(bad["ct"]))
    ct[-1] ^= 1
    bad["ct"] = base64.b64encode(bytes(ct)).decode()
    st, e = _post("/v1/analyze", bad)
    p3 = st == 400 and e.get("detail") == "pq_envelope_decrypt_failed"
    print(f"[altérée]   {st}  detail={e.get('detail')}")
    ok &= p3

    # 4) /deep ENVELOPPÉ sans entitlement -> 402 (le déchiffrement précède le contrôle Pro)
    st, e = _post("/v1/analyze/deep", seal_json(MSG, pub))
    p4 = st == 402
    print(f"[deep 402]  {st}  detail={e.get('detail')}")
    ok &= p4

    print("\nB3 :", "OK \u2705 — enveloppe déchiffrée de bout en bout, rétro-compat OK."
          if ok else "\u00c9CHEC \u274c")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
