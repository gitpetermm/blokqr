import ast

FILES = {
    'app/schemas.py': (
        'report_canonical',
        [
            (
                '''    verdict: Verdict
    score: int = Field(..., ge=0, le=100)
    report: AnalysisReport
    client_nonce: str''',
                '''    verdict: Verdict
    score: int = Field(..., ge=0, le=100)
    # Rapport sérialisé EXACTEMENT tel qu'il a été haché dans report_sha256. Le
    # client hache ces octets et les compare à report_sha256 (lui-même signé) :
    # la capture, la destination finale et les raisons sont ainsi RÉELLEMENT
    # authentifiées (parité garantie, sans re-sérialisation côté client).
    report_canonical: str = Field(
        default="", description="JSON canonique exact du rapport (préimage de report_sha256)."
    )
    client_nonce: str''',
            ),
        ],
    ),
    'app/pipeline.py': (
        'report_canonical',
        [
            (
                '''def _report_sha256(report: AnalysisReport) -> str:
    """SHA-256 du rapport complet (lie capture + raisons à la signature)."""
    return hashlib.sha256(report.model_dump_json().encode("utf-8")).hexdigest()


def _build_canonical(verdict: Verdict, score: int, report_sha256: str,''',
                '''def _build_canonical(verdict: Verdict, score: int, report_sha256: str,''',
            ),
            (
                '''    report_hash = _report_sha256(report)
    canonical = _build_canonical(
        verdict, score, report_hash, request.client_nonce, issued_at, expires_at, signer.key_id
    )
    bundle = signer.sign(canonical)
    return SignedVerdict(
        verdict=verdict,
        score=score,
        report=report,
        client_nonce=request.client_nonce,
        issued_at=issued_at,
        expires_at=expires_at,
        report_sha256=report_hash,
        canonical_payload=canonical,
        signature_ed25519_b64=bundle.ed25519_b64,
        signature_mldsa65_b64=bundle.mldsa65_b64,
        public_key_ed25519_b64=bundle.public_key_ed25519_b64,
        key_id=bundle.key_id,
    )''',
                '''    # Préimage canonique EXACTE du rapport : on signe son SHA-256 et on transmet
    # la chaîne elle-même, pour que le client hache octet pour octet (parité
    # garantie, sans re-sérialisation côté client -> insensible aux flottants).
    report_canonical = report.model_dump_json()
    report_hash = hashlib.sha256(report_canonical.encode("utf-8")).hexdigest()
    canonical = _build_canonical(
        verdict, score, report_hash, request.client_nonce, issued_at, expires_at, signer.key_id
    )
    bundle = signer.sign(canonical)
    return SignedVerdict(
        verdict=verdict,
        score=score,
        report_canonical=report_canonical,
        client_nonce=request.client_nonce,
        issued_at=issued_at,
        expires_at=expires_at,
        report_sha256=report_hash,
        canonical_payload=canonical,
        signature_ed25519_b64=bundle.ed25519_b64,
        signature_mldsa65_b64=bundle.mldsa65_b64,
        public_key_ed25519_b64=bundle.public_key_ed25519_b64,
        key_id=bundle.key_id,
    )''',
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
    print(f'[ok] {path} patche')
