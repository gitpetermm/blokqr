"""
Point d'entrée FastAPI de BlokQR.
Configure : durcissement des en-têtes HTTP, CORS restreint, limitation de
débit (anti-abus), et initialisation du signataire de verdicts au démarrage.
"""
from __future__ import annotations
import logging
import time
from collections import OrderedDict, deque
from contextlib import asynccontextmanager
from typing import Deque
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware
from app.api.routes import router
from app.billing.rtdn import router as rtdn_router   # NOUVEAU : récepteur RTDN
from app.config import get_settings
from app.logging_setup import configure_logging as configure_privacy_logging
from app.analyzers.dynamic_sandbox import prewarm_sandbox, shutdown_sandbox
from app.intel.web_risk import WebRiskClient
from app.security.signing import VerdictSigner
from app.security.key_manifest import ManifestSigner
from app.security.pq_envelope import GatewayKeys
settings = get_settings()
def _configure_logging() -> None:
    """Configure le logging applicatif.
    Sans cette configuration, les `logger.info(...)` / `logger.warning(...)`
    de nos modules (app.api.routes, app.security.play_integrity, etc.) ne
    sont pas propagés à la sortie standard du conteneur : uvicorn ne
    configure QUE son propre logger ("uvicorn", "uvicorn.access"). On force
    ici l'installation d'un handler sur le logger racine.
    Idempotent : si un handler est déjà attaché (rechargement à chaud, tests),
    on ne duplique pas.
    """
    root = logging.getLogger()
    if not root.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter(
            "%(asctime)s %(levelname)s %(name)s: %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S",
        ))
        root.addHandler(handler)
        root.setLevel(logging.INFO)
    elif root.level == logging.NOTSET or root.level > logging.INFO:
        # Déjà configuré (par uvicorn ou un autre lifespan) -- on ajuste juste
        # le niveau si on est plus restrictif.
        root.setLevel(logging.INFO)
    # Confidentialité (privacy-first) : rétrograde httpx/httpcore en WARNING
    # (sinon ils impriment les URLs scannées + la clé Web Risk en clair) et
    # installe un filtre de rédaction sur les handlers racine + uvicorn.
    # Voir app/logging_setup.py. Appelé APRÈS l'installation du handler racine
    # pour que le filtre s'y attache.
    configure_privacy_logging()
@asynccontextmanager
async def lifespan(app: FastAPI):
    _configure_logging()
    # Initialisation du matériel cryptographique une seule fois.
    signer = VerdictSigner(
        ed25519_seed_b64=settings.verdict_signing_seed_b64,
        enable_pq=settings.enable_pq_signature,
        key_path=settings.verdict_key_path,
    )
    app.state.signer = signer
    # Racine de confiance SLH-DSA + clés de passerelle ML-KEM.
    app.state.manifest_signer = ManifestSigner(settings.sphincs_root_key_path)
    app.state.gateway_keys = (
        GatewayKeys.load_or_create(settings.gateway_key_path)
        if settings.enable_pq_envelope else None
    )
    # app.state.gateway_keys = GatewayKeys.generate() if settings.enable_pq_envelope else None
    # Pré-chauffage du bac à sable (M2c) : démarre un Chromium « chaud » pour
    # supprimer le coût à froid de la première analyse approfondie. Best-effort :
    # un échec n'empêche pas le démarrage (le pool relancera à la demande).
    app.state.sandbox_warm = await prewarm_sandbox(settings)
    # Client Web Risk (Google Cloud) -- enrichissement de threat intel. Best-
    # effort : si la clé n'est pas configurée, le client reste None et le reste
    # de l'analyse fonctionne sans cette source. Aucune erreur ne remonte au
    # client si Web Risk est indisponible (fail-safe).
    app.state.web_risk = (
        WebRiskClient(api_key=settings.web_risk_api_key)
        if settings.web_risk_api_key else None
    )
    try:
        yield
    finally:
        if app.state.web_risk is not None:
            await app.state.web_risk.aclose()
        await shutdown_sandbox()
app = FastAPI(
    title="BlokQR",
    description="Service d'analyse de menaces pour scanner QR sécurisé (Security by Design).",
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/docs" if settings.debug else None,   # Swagger désactivé en prod.
    redoc_url=None,
    openapi_url="/openapi.json" if settings.debug else None,
)
# --------------------------------------------------------------------------- #
#  Middleware : en-têtes de sécurité
# --------------------------------------------------------------------------- #
class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Content-Security-Policy"] = "default-src 'none'"
        response.headers["Cache-Control"] = "no-store"
        response.headers["Permissions-Policy"] = "geolocation=(), camera=(), microphone=()"
        if request.url.scheme == "https":
            response.headers["Strict-Transport-Security"] = (
                "max-age=63072000; includeSubDomains; preload"
            )
        return response
# --------------------------------------------------------------------------- #
#  Middleware : limitation de débit en mémoire (fenêtre glissante)
# --------------------------------------------------------------------------- #
class RateLimitMiddleware(BaseHTTPMiddleware):
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
        # /v1/billing/rtdn : exempté du rate-limit. Les pushs RTDN arrivent tous
        # depuis quelques IP de Google Pub/Sub et partageraient un même bucket ;
        # une rafale (renouvellements simultanés) serait injustement limitée
        # (429). L'endpoint est protégé par son secret ?token=, pas par l'IP.
        if request.url.path in ("/health", "/pubkey", "/manifest", "/pq-pubkey", "/v1/billing/rtdn"):
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
        return await call_next(request)
app.add_middleware(SecurityHeadersMiddleware)
app.add_middleware(
    RateLimitMiddleware,
    limit_per_minute=settings.rate_limit_per_minute,
    trust_xff=settings.trust_forwarded_for,
    max_tracked_ips=settings.rate_limit_max_tracked_ips,
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_allowed_origins,
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type"],
)
app.include_router(router)
app.include_router(rtdn_router)   # NOUVEAU : expose POST /v1/billing/rtdn
@app.get("/", include_in_schema=False)
async def root():
    return {"service": settings.service_name, "status": "online"}
@app.get("/health")
async def health():
    # Public : strict minimum
    return {"status": "ok"}
@app.get("/internal/health")
async def internal_health(request: Request):
    # Détaillé : protégé par le filtre IP de Caddy (/internal/*)
    #
    # Note : on lit les valeurs REELLEMENT en vigueur (pas l'intention de la
    # config). Par exemple :
    #   - settings.enable_dynamic_sandbox = intention (config),
    #     mais le bac à sable peut être indisponible si Playwright manque.
    #   - signer.pq_enabled = réalité (charge OK de liboqs/ML-DSA-65), plus
    #     fiable que settings.enable_pq_signature (qui ne dit que l'intention).
    signer = request.app.state.signer
    return {
        "status": "ok",
        "service": settings.service_name,
        "environment": settings.environment,
        "dynamic_sandbox": bool(settings.enable_dynamic_sandbox),
        "pq_signature": bool(signer.pq_enabled),
        "web_risk": bool(getattr(request.app.state, "web_risk", None) is not None),
        "pq_envelope": bool(getattr(request.app.state, "gateway_keys", None) is not None),
    }
