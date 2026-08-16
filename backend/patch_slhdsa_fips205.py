import ast

FILES = {
    'app/security/pqc.py': (
        'import slhdsa as _slh',
        [
            (
                '''from pqcrypto.sign import sphincs_sha2_128s_simple as _slhdsa''',
                '''import slhdsa as _slh  # SLH-DSA (FIPS 205), implementation pure-Python''',
            ),
            (
                '''  - SLH-DSA / SPHINCS+ (FIPS 205) : signature du MANIFESTE DE CLÉS (racine de''',
                '''  - SLH-DSA-SHA2-128s (FIPS 205) : signature du MANIFESTE DE CLÉS (racine de''',
            ),
            (
                '''def slhdsa_generate():
    return _slhdsa.generate_keypair()


def slhdsa_sign(secret_key: bytes, message: bytes) -> bytes:
    return _slhdsa.sign(secret_key, message)


def slhdsa_verify(public_key: bytes, message: bytes, signature: bytes) -> bool:
    try:
        return _slhdsa.verify(public_key, message, signature)
    except Exception:  # noqa: BLE001
        return False''',
                '''_SLH_PARAM = _slh.sha2_128s  # SLH-DSA-SHA2-128s


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
        return False''',
            ),
        ],
    ),
}

for path, (marker, prs) in FILES.items():
    s = open(path, encoding='utf-8').read()
    if marker in s:
        print(f'[skip] {path} : deja patche'); continue
    for i, (old, new) in enumerate(prs, 1):
        n = s.count(old)
        assert n == 1, f'[ABANDON] {path} ancre #{i} trouvee {n} fois - rien ecrit'
        s = s.replace(old, new, 1)
    ast.parse(s)
    open(path, 'w', encoding='utf-8').write(s)
    print(f'[ok] {path} patche (SLH-DSA FIPS 205)')
