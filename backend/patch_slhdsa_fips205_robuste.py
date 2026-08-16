import ast

PATH = "app/security/pqc.py"
s = open(PATH, encoding="utf-8").read()

if "import slhdsa as _slh" in s:
    print("[skip] pqc.py deja patche (SLH-DSA FIPS 205)")
    raise SystemExit(0)

# 1) Import : remplacer SPHINCS+ round-3 (PQClean) par la lib SLH-DSA FIPS 205.
IMP_OLD = "from pqcrypto.sign import sphincs_sha2_128s_simple as _slhdsa"
assert s.count(IMP_OLD) == 1, "[ABANDON] ligne import SPHINCS introuvable - rien ecrit"
s = s.replace(
    IMP_OLD,
    "import slhdsa as _slh  # SLH-DSA (FIPS 205), implementation pure-Python",
    1,
)

# 2) Fonctions racine : reimplementer en SLH-DSA pur FIPS 205 (contrat (pub, sk) conserve).
FN_OLD = '''def slhdsa_generate():
    return _slhdsa.generate_keypair()


def slhdsa_sign(secret_key: bytes, message: bytes) -> bytes:
    return _slhdsa.sign(secret_key, message)


def slhdsa_verify(public_key: bytes, message: bytes, signature: bytes) -> bool:
    try:
        return _slhdsa.verify(public_key, message, signature)
    except Exception:  # noqa: BLE001
        return False'''

FN_NEW = '''_SLH_PARAM = _slh.sha2_128s  # SLH-DSA-SHA2-128s


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
        return False'''

assert s.count(FN_OLD) == 1, "[ABANDON] bloc fonctions slhdsa_* introuvable - rien ecrit"
s = s.replace(FN_OLD, FN_NEW, 1)

# 3) Etiquette normative : normaliser vers la valeur FIPS 205 (gere l'etat original ET C6).
if 'ALG_ROOT_PQ = "SPHINCS+-SHA2-128s"' in s:
    s = s.replace('ALG_ROOT_PQ = "SPHINCS+-SHA2-128s"', 'ALG_ROOT_PQ = "SLH-DSA-SHA2-128s"', 1)
assert 'ALG_ROOT_PQ = "SLH-DSA-SHA2-128s"' in s, "[ABANDON] ALG_ROOT_PQ inattendu - rien ecrit"

# 4) Docstring : normalisation cosmetique (best-effort, sans echec si absente).
for _old in (
    "  - SLH-DSA / SPHINCS+ (FIPS 205) : signature du MANIFESTE DE CLÉS (racine de",
    "  - SPHINCS+-SHA2-128s (round 3) : signature du MANIFESTE DE CLÉS (racine de",
):
    if _old in s:
        s = s.replace(
            _old,
            "  - SLH-DSA-SHA2-128s (FIPS 205) : signature du MANIFESTE DE CLÉS (racine de",
            1,
        )
        break

ast.parse(s)
open(PATH, "w", encoding="utf-8").write(s)
print("[ok] pqc.py patche (SLH-DSA FIPS 205) ; ALG_ROOT_PQ = SLH-DSA-SHA2-128s")
