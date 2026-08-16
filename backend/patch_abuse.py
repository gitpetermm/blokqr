import ast

FILES = {
    'app/config.py': (
        'overall_budget_seconds',
        [
            (
                '''    # --- Limitation de débit (anti-abus du service lui-même) -----------------
    rate_limit_per_minute: int = 30''',
                '''    # --- Limitation de débit (anti-abus du service lui-même) -----------------
    rate_limit_per_minute: int = 30
    # Faire confiance à X-Forwarded-For (vrai derrière un reverse-proxy comme
    # Caddy) : la limite s'applique alors par CLIENT réel, pas par IP du proxy.
    # Anti-spoof : on ne retient que la DERNIÈRE entrée (ajoutée par notre proxy).
    trust_forwarded_for: bool = True
    # Plafond d'IP suivies (borne mémoire du limiteur ; éviction LRU au-delà).
    rate_limit_max_tracked_ips: int = 20000''',
            ),
            (
                '''    dynamic_budget_seconds: float = 16.0''',
                '''    dynamic_budget_seconds: float = 16.0
    # Budget TOTAL du pipeline d'analyse. Au-delà, on renvoie immédiatement un
    # verdict SIGNÉ « unknown » (fail-closed) : le client ne subit jamais de
    # timeout réseau, quelle que soit la lenteur d'une dépendance externe.
    # À garder nettement sous le callTimeout du client (35 s).
    overall_budget_seconds: float = 22.0''',
            ),
        ],
    ),
    'app/pipeline.py': (
        'build_timeout_verdict',
        [
            (
                '''    return reasons


async def analyze(''',
                '''    return reasons


def _finalize(verdict: Verdict, score: int, report: AnalysisReport,
              request: AnalyzeRequest, settings: Settings,
              signer: VerdictSigner) -> SignedVerdict:
    """Horodate, signe (Ed25519 + ML-DSA-65) et emballe le verdict."""
    now = datetime.now(timezone.utc)
    issued_at = now.isoformat()
    expires_at = (now + timedelta(seconds=settings.verdict_ttl_seconds)).isoformat()
    report_hash = _report_sha256(report)
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
    )


def build_timeout_verdict(request: AnalyzeRequest, settings: Settings,
                          signer: VerdictSigner) -> SignedVerdict:
    """Verdict SIGNÉ fail-closed lorsque le budget global est dépassé.

    Garantit une réponse authentifiée sous le délai réseau du client, même si
    une étape (résolution, rendu, threat intel) reste bloquée. On ne certifie
    jamais « sûr » une analyse non terminée : verdict UNKNOWN (prudence).
    """
    classified = classify(request.raw_payload)
    report = AnalysisReport(
        payload_type=classified.payload_type,
        displayed_value=classified.normalized,
    )
    report.signals = [Signal(
        code="analysis_timeout",
        title="Analyse interrompue (délai dépassé)",
        detail="Le service n'a pas pu terminer l'analyse dans le temps imparti ; "
               "verdict prudent rendu par sécurité. Vous pouvez réessayer.",
        severity=Severity.LOW, weight=0, source="pipeline",
    )]
    report.reasons = [report.signals[0].title]
    return _finalize(Verdict.UNKNOWN, 0, report, request, settings, signer)


async def analyze(''',
            ),
            (
                '''    # 9. Signature du verdict (liée au hash du rapport complet).
    now = datetime.now(timezone.utc)
    issued_at = now.isoformat()
    expires_at = (now + timedelta(seconds=settings.verdict_ttl_seconds)).isoformat()
    report_hash = _report_sha256(report)
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
                '''    # 9. Signature du verdict (liée au hash du rapport complet).
    return _finalize(verdict, score, report, request, settings, signer)''',
            ),
        ],
    ),
    'app/api/routes.py': (
        'build_timeout_verdict',
        [
            (
                '''from __future__ import annotations

from fastapi import APIRouter, Depends, Request''',
                '''from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, Request''',
            ),
            (
                '''from app.pipeline import analyze''',
                '''from app.pipeline import analyze, build_timeout_verdict''',
            ),
            (
                '''    return await analyze(payload, settings, signer)''',
                '''    # Budget global : garantit une réponse SIGNÉE sous le délai réseau du client,
    # même si une dépendance (rendu, threat intel) reste bloquée -> fail-closed.
    try:
        return await asyncio.wait_for(
            analyze(payload, settings, signer),
            timeout=settings.overall_budget_seconds,
        )
    except asyncio.TimeoutError:
        return build_timeout_verdict(payload, settings, signer)''',
            ),
        ],
    ),
    'app/main.py': (
        'move_to_end',
        [
            (
                '''from collections import defaultdict, deque''',
                '''from collections import OrderedDict, deque''',
            ),
            (
                '''from typing import Deque, Dict''',
                '''from typing import Deque''',
            ),
            (
                '''class RateLimitMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, limit_per_minute: int) -> None:
        super().__init__(app)
        self.limit = limit_per_minute
        self.window = 60.0
        self.hits: Dict[str, Deque[float]] = defaultdict(deque)

    async def dispatch(self, request: Request, call_next):
        if request.url.path in ("/health", "/pubkey", "/manifest", "/pq-pubkey"):
            return await call_next(request)

        client_ip = request.client.host if request.client else "unknown"
        now = time.monotonic()
        bucket = self.hits[client_ip]
        while bucket and now - bucket[0] > self.window:
            bucket.popleft()
        if len(bucket) >= self.limit:
            return JSONResponse(
                status_code=429,
                content={"detail": "Trop de requêtes, réessayez plus tard."},
                headers={"Retry-After": "60"},
            )
        bucket.append(now)
        return await call_next(request)''',
                '''class RateLimitMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, limit_per_minute: int, trust_xff: bool = True,
                 max_tracked_ips: int = 20000) -> None:
        super().__init__(app)
        self.limit = limit_per_minute
        self.window = 60.0
        self.trust_xff = trust_xff
        self.max_tracked = max_tracked_ips
        # OrderedDict pour une éviction LRU bornée (anti-DoS mémoire).
        self.hits: "OrderedDict[str, Deque[float]]" = OrderedDict()

    def _client_ip(self, request: Request) -> str:
        # Derrière un reverse-proxy de confiance (Caddy), la vraie IP client est
        # la DERNIÈRE entrée de X-Forwarded-For (celle ajoutée par le proxy) :
        # les valeurs en amont peuvent être forgées par le client -> on les ignore.
        if self.trust_xff:
            xff = request.headers.get("x-forwarded-for")
            if xff:
                return xff.split(",")[-1].strip()
        return request.client.host if request.client else "unknown"

    async def dispatch(self, request: Request, call_next):
        if request.url.path in ("/health", "/pubkey", "/manifest", "/pq-pubkey"):
            return await call_next(request)

        client_ip = self._client_ip(request)
        now = time.monotonic()
        bucket = self.hits.get(client_ip)
        if bucket is None:
            bucket = deque()
            self.hits[client_ip] = bucket
            # Plafond mémoire : éviction des IP vues le plus anciennement.
            while len(self.hits) > self.max_tracked:
                self.hits.popitem(last=False)
        else:
            self.hits.move_to_end(client_ip)  # marque comme récemment vue

        while bucket and now - bucket[0] > self.window:
            bucket.popleft()
        if len(bucket) >= self.limit:
            retry = max(1, int(self.window - (now - bucket[0])))
            return JSONResponse(
                status_code=429,
                content={"detail": "Trop de requêtes, réessayez plus tard."},
                headers={"Retry-After": str(retry)},
            )
        bucket.append(now)
        return await call_next(request)''',
            ),
            (
                '''app.add_middleware(RateLimitMiddleware, limit_per_minute=settings.rate_limit_per_minute)''',
                '''app.add_middleware(
    RateLimitMiddleware,
    limit_per_minute=settings.rate_limit_per_minute,
    trust_xff=settings.trust_forwarded_for,
    max_tracked_ips=settings.rate_limit_max_tracked_ips,
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
