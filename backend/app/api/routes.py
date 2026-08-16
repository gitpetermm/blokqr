"""Routes HTTP de BlokQR."""
from __future__ import annotations
import logging
import asyncio
import json
import secrets
from fastapi import APIRouter, Depends, Request, HTTPException, Response
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from app.config import Settings, get_settings
from app.pipeline import analyze, build_timeout_verdict
from app.analyzers.reputation_kanon import get_store
from app.billing.play_verifier import acknowledge_subscription, verify_subscription
from app.security.entitlement import sign_entitlement, verify_entitlement
from app.security.hmac_auth import verify_hmac
from app.security.install_token import issue_install, mark_pro, is_pro
from app.schemas import (
    AnalyzeRequest,
    BillingVerifyRequest,
    BillingVerifyResponse,
    GatewayKeyResponse,
    HealthResponse,
    KeyManifestResponse,
    PublicKeyResponse,
    ReputationMatch,
    ReputationRequest,
    ReputationResponse,
    SignedVerdict,
    Verdict,
)
from app.schemas_quota import (
    InstallRequest,
    InstallResponse,
    QuotaStatus,
)
from app.quota import (
    peek as quota_peek,
    refund as quota_refund,
    try_consume,
    try_consume_deep_free,  # M4_DEEP_FREE
    refund_deep_free,       # M4_DEEP_FREE
)
from app.security.signing import VerdictSigner
from app.security.pq_envelope import open_json
from app.security import play_integrity
import os
logger = logging.getLogger(__name__)
router = APIRouter()
def get_signer(request: Request) -> VerdictSigner:
    """Récupère le signataire unique attaché au cycle de vie de l'application."""
    return request.app.state.signer
# --------------------------------------------------------------------------- #
#  Enveloppe de confidentialité (B2) : corps EN CLAIR ou ENVELOPPÉ
# --------------------------------------------------------------------------- #
_ENVELOPE_KEYS = frozenset({"ct_mlkem", "epk_x25519", "ct"})
async def _read_body_once(request: Request) -> bytes:
    """Lit le corps brut une seule fois et le mémorise sur la requête.
    Nécessaire car HMAC + JSON parsing + enveloppe doivent tous voir les MÊMES
    octets exacts du body (signature HMAC reproductible).
    """
    if not hasattr(request.state, "_raw_body"):
        request.state._raw_body = await request.body()
    return request.state._raw_body
async def decode_analyze_request(request: Request) -> AnalyzeRequest:
    """Construit l'AnalyzeRequest depuis un corps EN CLAIR ou ENVELOPPÉ.
    Rétro-compatible : un JSON classique {raw_payload, ...} est accepté tel quel.
    Si le corps est une enveloppe hybride {ct_mlkem, epk_x25519, ct}, elle est
    déchiffrée avec les clés de passerelle (ML-KEM-768 + X25519) AVANT validation.
    Politique : une enveloppe reçue alors que l'enveloppe est désactivée ou
    illisible renvoie 400 -> le client peut basculer en clair (fail-open côté
    app, TLS protégeant déjà le transport). Le verdict reste SIGNÉ :
    confidentialité n'est pas authenticité.
    """
    raw = await _read_body_once(request)
    try:
        body = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        raise HTTPException(status_code=422, detail="invalid_json")
    if not isinstance(body, dict):
        raise HTTPException(status_code=422, detail="invalid_body")
    if _ENVELOPE_KEYS <= body.keys():
        gk = getattr(request.app.state, "gateway_keys", None)
        if gk is None:
            raise HTTPException(status_code=400, detail="pq_envelope_disabled")
        try:
            body = open_json(body, gk)
        except Exception:
            raise HTTPException(status_code=400, detail="pq_envelope_decrypt_failed")
        if not isinstance(body, dict):
            raise HTTPException(status_code=422, detail="invalid_envelope_payload")
    try:
        return AnalyzeRequest(**body)
    except ValidationError as exc:
        raise HTTPException(status_code=422, detail=exc.errors())
# --------------------------------------------------------------------------- #
#  Endpoints système (inchangés)
# --------------------------------------------------------------------------- #
@router.get("/manifest", response_model=KeyManifestResponse, tags=["system"])
async def manifest(request: Request,
                   signer: VerdictSigner = Depends(get_signer)) -> KeyManifestResponse:
    """Manifeste de clés signé SLH-DSA. Le client n'épingle que la racine SLH-DSA."""
    ms = request.app.state.manifest_signer
    gk = request.app.state.gateway_keys
    mlkem_pub = gk.public_bundle["mlkem768_pub"] if gk else ""
    m = ms.get_or_build(
        version=1, key_id=signer.key_id,
        ed25519_pub_b64=signer.public_key_ed25519_b64,
        mldsa65_pub_b64=signer.public_key_mldsa65_b64,
        mlkem768_pub_b64=mlkem_pub,
    )
    return KeyManifestResponse(
        version=m.version, key_id=m.key_id, ed25519_pub_b64=m.ed25519_pub_b64,
        mldsa65_pub_b64=m.mldsa65_pub_b64, mlkem768_pub_b64=m.mlkem768_pub_b64,
        issued_at=m.issued_at, not_after=m.not_after, root_alg=m.root_alg,
        canonical=m.canonical, sig_slhdsa_b64=m.sig_slhdsa_b64, root_pub_b64=m.root_pub_b64,
    )
@router.get("/pq-pubkey", response_model=GatewayKeyResponse, tags=["system"])
async def pq_pubkey(request: Request) -> GatewayKeyResponse:
    """Clés publiques de la passerelle pour l'enveloppe hybride ML-KEM + X25519."""
    gk = request.app.state.gateway_keys
    if not gk:
        return GatewayKeyResponse(mlkem768_pub="", x25519_pub="", alg="disabled")
    b = gk.public_bundle
    return GatewayKeyResponse(mlkem768_pub=b["mlkem768_pub"],
                              x25519_pub=b["x25519_pub"], alg=b["alg"])
@router.get("/health", response_model=HealthResponse, tags=["system"])
async def health(settings: Settings = Depends(get_settings),
                 signer: VerdictSigner = Depends(get_signer)) -> HealthResponse:
    return HealthResponse(
        service=settings.service_name,
        environment=settings.environment,
        dynamic_sandbox=settings.enable_dynamic_sandbox,
        pq_signature=signer.pq_enabled,
    )
@router.get("/pubkey", response_model=PublicKeyResponse, tags=["system"])
async def pubkey(signer: VerdictSigner = Depends(get_signer)) -> PublicKeyResponse:
    """Expose la clé publique de signature (à épingler côté client)."""
    return PublicKeyResponse(
        key_id=signer.key_id,
        public_key_ed25519_b64=signer.public_key_ed25519_b64,
        public_key_mldsa65_b64=signer.public_key_mldsa65_b64,
        pq_enabled=signer.pq_enabled,
    )
# --------------------------------------------------------------------------- #
#  Provisionnement d'installation (POST /v1/install) -- Paquet 2 + Integrity
# --------------------------------------------------------------------------- #
@router.post("/v1/install", response_model=InstallResponse, tags=["install"])
async def install_route(
    request: Request,
    body: InstallRequest | None = None,
    settings: Settings = Depends(get_settings),
) -> InstallResponse:
    """
    Premier appel du client : génère un install_id + un secret HMAC.
    Sécurité (couches successives, du bas vers le haut) :
      - TLS + épinglage de certificat côté client (protection canal) ;
      - Optionnellement enveloppe PQ ML-KEM-768 + X25519 (Paquet 1) ;
      - Vérification d'intégrité Google Play Integrity (Paquet 7, ANTI-ABUS) :
          * confirme que la requête provient d'une vraie installation Android,
            depuis le Play Store, sur un appareil non compromis (pas
            d'émulateur, pas d'app modifiée) ;
          * lié à un nonce unique : un token capturé ne peut être rejoué ;
          * mode initial PERMISSIF : un token absent ou invalide est
            JOURNALISÉ mais n'empêche pas le provisionnement (migration
            douce). Bascule en strict via `require_integrity=True`.
    Idempotence : NON. Appeler /v1/install deux fois génère deux installations
    distinctes (à dessein -- évite le replay d'un install_id voulu). L'app
    n'appelle CET endpoint qu'une fois, au premier lancement, puis stocke
    durablement le résultat en EncryptedSharedPreferences.
    """
    # Lit le header Integrity (case-insensitive en HTTP, FastAPI normalise).
    integrity_token = (request.headers.get("x-integrity-token") or "").strip()
    nonce = (body.nonce if body else None) or ""
    # --- Cas 1 : token absent ------------------------------------------------
    if not integrity_token:
        if settings.require_integrity:
            # Mode strict : on refuse net.
            logger.warning(
                "install_refused: integrity_token manquant (mode strict)",
            )
            raise HTTPException(
                status_code=401,
                detail="integrity_token_missing",
            )
        # Mode permissif : on journalise et on provisionne quand même.
        logger.info("install_permissive: aucun token Integrity fourni")
    # --- Cas 2 : token présent -> vérification Google ------------------------
    else:
        verdict = await play_integrity.verify(
            integrity_token=integrity_token,
            expected_nonce=nonce,
            settings=settings,
        )
        if verdict.ok:
            # Journalisation succinte (pas de PII, juste les verdicts).
            logger.info(
                "install_verified: device=%s app=%s license=%s",
                verdict.device_recognition_verdicts,
                verdict.app_recognition_verdict,
                verdict.app_licensing_verdict,
            )
        else:
            # Rejet : log structuré avec la raison.
            logger.warning(
                "install_integrity_rejected: reason=%s detail=%s "
                "device=%s app=%s license=%s",
                verdict.reason, verdict.detail,
                verdict.device_recognition_verdicts,
                verdict.app_recognition_verdict,
                verdict.app_licensing_verdict,
            )
            if settings.require_integrity:
                raise HTTPException(
                    status_code=401,
                    detail=f"integrity_rejected:{verdict.reason}",
                )
            # Mode permissif : on note et on continue.
    # --- Provisionnement effectif (inchangé) --------------------------------
    install_id, secret_b64 = await issue_install()
    return InstallResponse(
        install_id=install_id,
        hmac_secret_b64=secret_b64,
        free_daily_quota=settings.free_daily_quota,
        pro_daily_quota=settings.pro_daily_quota,
    )
# --------------------------------------------------------------------------- #
#  Lecture du quota (GET /v1/quota) -- Paquet 2
# --------------------------------------------------------------------------- #
@router.get("/v1/quota", response_model=QuotaStatus, tags=["quota"])
async def quota_route(
    request: Request,
    settings: Settings = Depends(get_settings),
) -> QuotaStatus:
    """
    Lecture de l'état du quota du jour, sans consommer.
    Le client appelle cet endpoint au démarrage et avant chaque scan
    pour afficher « N/M analyses aujourd'hui ». Pas d'incrément, donc pas
    de pénalité réseau si appelé fréquemment.
    Requiert X-Install-Id (et HMAC si require_hmac=True). Pas de body à signer
    sur un GET : le client signe un body vide (b"").
    """
    body = await _read_body_once(request)
    install_id = await verify_hmac(request, body, settings)
    st = await quota_peek(install_id, settings)
    return QuotaStatus(**st.to_json())
# --------------------------------------------------------------------------- #
#  Helpers de gating quota pour /v1/analyze*
# --------------------------------------------------------------------------- #
def _sign_quota_attestation(
    signer: VerdictSigner, install_id: str, st_json: dict,
) -> str:
    """
    Signature HYBRIDE Ed25519 + ML-DSA-65 attestant l'état du quota au moment
    de l'erreur 429. Cohérent avec « toutes les signatures restent hybrides ».
    Le client peut vérifier cette attestation pour s'assurer que le serveur
    ne ment pas sur la limite (défense en profondeur côté client).
    """
    canonical = json.dumps(
        {"install": install_id, **st_json},
        separators=(",", ":"), sort_keys=True, ensure_ascii=False,
    )
    bundle = signer.sign(canonical)
    return json.dumps(
        {
            "canonical": canonical,
            "key_id": bundle.key_id,
            "sig_ed25519_b64": bundle.ed25519_b64,
            "sig_mldsa65_b64": bundle.mldsa65_b64,
            "alg": "Ed25519+ML-DSA-65",
        },
        separators=(",", ":"), ensure_ascii=False,
    )
def _quota_exceeded_response(
    install_id: str, st, signer: VerdictSigner,
) -> JSONResponse:
    """Construit la réponse 429 enrichie + headers + attestation signée."""
    st_json = st.to_json()
    attestation = _sign_quota_attestation(signer, install_id, st_json)
    body = {
        "detail": "quota_exceeded",
        **st_json,
        "recommendation": {
            "primary": "upgrade_pro" if not st.is_pro else "contact_support",
            "secondary": "retry_after",
            "retry_after_seconds": st.reset_in_seconds,
        },
        "signed_attestation": attestation,
    }
    return JSONResponse(
        status_code=429, content=body, headers=st.to_headers(),
    )
async def _ensure_pro_from_entitlement(
    install_id: str,
    payload: AnalyzeRequest,
    signer: VerdictSigner,
    settings: Settings,
) -> None:
    """Auto-réparation du statut Pro côté QUOTA.
    Si le client présente un entitlement Pro signé VALIDE, on s'assure que la
    marque Redis pro:{install_id} est posée (idempotent, best-effort). C'est le
    filet qui garde le badge Pro (côté Play/app) et le quota Pro (côté serveur)
    synchrones, même lorsque le marquage au moment de l'achat (/v1/billing/verify)
    a été manqué : réinstallation (nouvel install_id) ou course
    achat/provisioning où X-Install-Id n'était pas encore disponible.
    N'échoue jamais : une erreur de marquage ne doit pas bloquer l'analyse.
    """
    ent = verify_entitlement(payload.entitlement or "", signer)
    if not (ent and ent.get("pro")):
        return
    try:
        if not await is_pro(install_id):
            await mark_pro(install_id, ttl_seconds=settings.entitlement_ttl_seconds)
    except Exception:
        logger.warning("auto-mark_pro échoué (entitlement valide)", exc_info=True)
# --------------------------------------------------------------------------- #
#  /v1/analyze  -- Paquet 2 : HMAC + quota appliqués
# --------------------------------------------------------------------------- #
@router.post("/v1/analyze", response_model=SignedVerdict, tags=["analysis"])
async def analyze_route(
    request: Request,
    settings: Settings = Depends(get_settings),
    signer: VerdictSigner = Depends(get_signer),
):
    """Analyse un contenu décodé et renvoie un verdict signé.
    Flux Paquet 2 :
      1. Vérification HMAC (mode permissif/strict selon settings.require_hmac).
         → 401 si install_id absent/inconnu ou HMAC invalide en strict.
      2. Décodage du corps (enveloppe PQ ou clair) puis AUTO-RÉPARATION Pro :
         un entitlement signé valide promeut l'installation en Pro avant le
         gating quota (badge Pro ⇔ quota Pro garantis synchrones).
      3. Consommation atomique du quota (1 unité).
         → 429 + verdict d'attestation hybride si dépassé.
      4. Analyse classique (asyncio.wait_for + fail-closed).
      5. Si verdict UNKNOWN (timeout), on rembourse l'unité (le scan n'a pas
         été rendu réellement).
      6. Réponse + headers X-Quota-*.
    """
    body = await _read_body_once(request)
    install_id = await verify_hmac(request, body, settings)
    # Décodage post-HMAC (enveloppe PQ ou JSON clair) puis validation Pydantic.
    # Fait AVANT le gating quota pour pouvoir lire un éventuel entitlement signé.
    payload: AnalyzeRequest = await decode_analyze_request(request)
    # Auto-réparation Pro : un entitlement signé valide promeut l'installation
    # en Pro (marque Redis paresseuse). Répare le quota après une réinstallation
    # ou si le marquage à l'achat a échoué, sans dépendre du timing de
    # /v1/billing/verify. Le 1er scan suffit alors à rétablir le quota Pro.
    await _ensure_pro_from_entitlement(install_id, payload, signer, settings)
    # Gating quota (voit désormais Pro si l'entitlement présenté est valide).
    st = await try_consume(install_id, settings)
    if not st.consumed:
        return _quota_exceeded_response(install_id, st, signer)
    # Analyse avec budget global (réponse SIGNÉE même en timeout).
    try:
        verdict = await asyncio.wait_for(
            analyze(payload, settings, signer),
            timeout=settings.overall_budget_seconds,
        )
    except asyncio.TimeoutError:
        verdict = build_timeout_verdict(payload, settings, signer)
        # Le scan n'a pas pu être rendu : on rembourse pour ne pas pénaliser.
        await quota_refund(install_id)
        # On relit l'état pour exposer le bon X-Quota-Used post-refund.
        st = await quota_peek(install_id, settings)
    # On retourne un JSONResponse pour pouvoir injecter les en-têtes de quota.
    return JSONResponse(
        status_code=200,
        content=verdict.model_dump(mode="json"),
        headers=st.to_headers(),
    )
# --------------------------------------------------------------------------- #
#  /v1/analyze/deep  -- Paquet 2 : HMAC + quota + entitlement
# --------------------------------------------------------------------------- #
@router.post("/v1/analyze/deep", response_model=SignedVerdict, tags=["analysis"])
async def analyze_deep_route(
    request: Request,
    settings: Settings = Depends(get_settings),
    signer: VerdictSigner = Depends(get_signer),
):
    """Analyse PROFONDE (Pro) : ajoute le rendu dynamique Chromium.
    Autorité Pro DOUBLE : un jeton d'entitlement signé valide OU la marque
    Redis pro:{install_id}. En l'absence des DEUX : 402 -> le client affiche
    le paywall. La marque pro: n'est jamais posée par le client : uniquement
    après un entitlement vérifié (auto-réparation /v1/analyze*) ou un achat
    validé par /v1/billing/verify. Accepter la marque comme autorité évite un
    402 quand le jeton signé a expiré entre deux rafraîchissements (abo testeur
    à renouvellement accéléré, ou session longue), alors que l'install est bien
    Pro côté serveur. Fenêtre de grâce bornée par le TTL de la marque (7 j).
    Le QUOTA suit la même source de vérité : Redis pro:{install_id}.
    """
    body = await _read_body_once(request)
    install_id = await verify_hmac(request, body, settings)
    payload: AnalyzeRequest = await decode_analyze_request(request)
    # RTDN : un abo révoqué / hold / expiré côté Play bloque l'accès deep, même
    # si le jeton signé du client n'a pas encore expiré (révocation immédiate).
    # Le quota suit déjà is_pro ; ici on neutralise aussi le jeton signé.
    from app.billing.rtdn import deep_revoked
    if await deep_revoked(install_id):
        raise HTTPException(status_code=402, detail="pro_required")
    # Autorité Pro (DOUBLE) : jeton d'entitlement signé valide OU marque Redis
    # pro:{install_id}. La marque n'est JAMAIS posée par le client : uniquement
    # après un entitlement vérifié (auto-réparation /v1/analyze*) ou un achat
    # validé par /v1/billing/verify. L'accepter comme autorité évite un 402
    # lorsque le jeton signé a expiré entre deux rafraîchissements (abo testeur
    # à renouvellement accéléré, ou session plus longue que le TTL du jeton),
    # alors que l'install est bien Pro côté serveur. Fenêtre de grâce bornée par
    # le TTL de la marque (entitlement_ttl_seconds).
    ent = verify_entitlement(payload.entitlement or "", signer)
    ent_pro = bool(ent and ent.get("pro"))
    pro = ent_pro or await is_pro(install_id)
    # M4_DEEP_FREE : si NON Pro, on tente d'accorder l'apercu approfondi offert
    # du jour (compteur Redis deepfree:, 1/jour par defaut). Accorde -> on
    # poursuit l'analyse profonde comme un Pro pour CE scan ; epuise -> 402.
    deep_free_granted = False
    if not pro:
        deep_free_granted = await try_consume_deep_free(install_id, settings)
        if not deep_free_granted:
            raise HTTPException(status_code=402, detail="pro_required")
    # Si un entitlement valide est présenté, on (re)pose la marque Pro
    # (idempotent, best-effort) pour garder quota et accès deep synchrones.
    if ent_pro:
        try:
            if not await is_pro(install_id):
                await mark_pro(install_id, ttl_seconds=settings.entitlement_ttl_seconds)
        except Exception:
            logger.warning("auto-mark_pro échoué (deep)", exc_info=True)
    # Gating quota (Pro -> quota Pro ; apercu offert -> compte comme 1 scan
    # standard, decision Option 1 : 7 analyses/jour dont la 1re approfondie).
    st = await try_consume(install_id, settings)
    if not st.consumed:
        # Le quota standard est plein : on rembourse l'apercu offert eventuel
        # (il n'a pas ete rendu) pour ne pas gacher l'offre du jour.
        if deep_free_granted:
            await refund_deep_free(install_id)
        return _quota_exceeded_response(install_id, st, signer)
    try:
        verdict = await asyncio.wait_for(
            analyze(payload, settings, signer, deep=True),
            timeout=settings.overall_budget_seconds,
        )
    except asyncio.TimeoutError:
        verdict = build_timeout_verdict(payload, settings, signer)
        await quota_refund(install_id)
        # L'analyse profonde n'a pas ete rendue : on rend aussi l'apercu offert.
        if deep_free_granted:
            await refund_deep_free(install_id)
        st = await quota_peek(install_id, settings)
    # M4_DEEP_FREE : signale au client la nature de ce resultat profond.
    headers = st.to_headers()
    headers["X-Deep-Free"] = "granted" if deep_free_granted else "pro"
    return JSONResponse(
        status_code=200,
        content=verdict.model_dump(mode="json"),
        headers=headers,
    )
# --------------------------------------------------------------------------- #
#  /v1/reputation (inchangé)
# --------------------------------------------------------------------------- #
@router.post("/v1/reputation", response_model=ReputationResponse, tags=["analysis"])
async def reputation_route(payload: ReputationRequest) -> ReputationResponse:
    """Réputation k-anonyme par préfixes de hash (vie privée maximale).
    Le client n'envoie QUE des préfixes de 4 octets : le serveur ne connaît
    jamais l'URL vérifiée. À placer derrière un relais OHTTP (RFC 9458) pour
    masquer également l'IP. Le client effectue la correspondance finale en local.
    """
    store = get_store()
    matches = store.search_prefixes(payload.prefixes)
    return ReputationResponse(
        matches=[
            ReputationMatch(full_hash_hex=m.full_hash_hex,
                            categories=m.categories, source=m.source)
            for m in matches
        ],
        store_size=store.size,
    )
def _entitlement_ttl(expiry_iso: str | None, settings: Settings) -> int:
    """TTL de l'entitlement = min(plafond config, temps jusqu'à expiration Play)."""
    from datetime import datetime, timezone
    cap = int(settings.entitlement_ttl_seconds)
    if not expiry_iso:
        return cap
    try:
        exp = datetime.fromisoformat(expiry_iso.replace("Z", "+00:00"))
    except ValueError:
        return cap
    remaining = int((exp - datetime.now(timezone.utc)).total_seconds())
    if remaining <= 0:
        return 60  # garde-fou minimal
    return min(cap, remaining)
# --------------------------------------------------------------------------- #
#  /v1/billing/verify -- Paquet 2 : marque aussi pro:{install_id} dans Redis
# --------------------------------------------------------------------------- #
@router.post("/v1/billing/verify", response_model=BillingVerifyResponse, tags=["billing"])
async def billing_verify_route(
    payload: BillingVerifyRequest,
    request: Request,
    settings: Settings = Depends(get_settings),
    signer: VerdictSigner = Depends(get_signer),
) -> BillingVerifyResponse:
    """Vérifie un achat Play et émet un entitlement Pro signé.
    Modèle privacy-first : le purchaseToken est vérifié de façon transactionnelle
    auprès de Google (aucune identité stockée). Si l'abonnement est actif, on
    acquitte l'achat (obligatoire sous 3 jours) puis on signe un entitlement court
    (réutilise la clé de verdict) que le client met en cache et présente sur
    /v1/analyze*.
    En plus de retourner l'entitlement signé au client, on marque pro:{install_id}
    dans Redis (TTL aligné sur l'expiration). C'est cette marque Redis qui fait
    foi pour le QUOTA quotidien côté Pro. FILET : si l'install_id n'est pas
    disponible ici (course achat/provisioning), l'auto-réparation sur /v1/analyze*
    posera la marque dès le 1er scan présentant l'entitlement signé.
    """
    if not settings.enable_billing_verify:
        raise HTTPException(status_code=503, detail="billing_disabled")
    try:
        result = await asyncio.to_thread(
            verify_subscription, payload.purchase_token, settings)
    except Exception:
        # Échec d'appel à Google (réseau, identifiants, token inconnu).
        raise HTTPException(status_code=502, detail="play_verify_failed")
    if not result.entitled:
        return BillingVerifyResponse(pro=False, entitlement=None,
                                     plan=result.plan, expiry=result.expiry_iso)
    # Acquittement best-effort (réessayé à la prochaine vérification si échec).
    if not result.acknowledged:
        try:
            await asyncio.to_thread(
                acknowledge_subscription, payload.purchase_token, settings)
        except Exception:
            pass
    ttl = _entitlement_ttl(result.expiry_iso, settings)
    # Marquage Pro pour le QUOTA. install_id facultatif sur ce endpoint :
    # si l'app l'envoie (X-Install-Id injecté par HmacInterceptor), on le
    # synchronise immédiatement ; sinon l'auto-réparation sur /v1/analyze* le
    # posera au 1er scan présentant l'entitlement signé.
    install_id = (request.headers.get("x-install-id") or "").strip()
    if install_id:
        try:
            await mark_pro(install_id, ttl_seconds=ttl)
        except Exception:
            # Marquage Pro est best-effort : ne pas bloquer la facturation.
            logger.warning("mark_pro échoué à l'achat (sera réparé au scan)",
                           exc_info=True)
        # RTDN : lien purchaseToken -> install pour le suivi du cycle de vie
        # (renouvellements / remboursements). Best-effort : ne bloque pas l'achat.
        try:
            from app.billing.rtdn import link_purchase
            await link_purchase(payload.purchase_token, install_id)
        except Exception:
            logger.warning("link_purchase échoué", exc_info=True)
    token = sign_entitlement(
        signer, pro=True, plan=result.plan or "pro",
        ttl_seconds=ttl, nonce=secrets.token_urlsafe(12))
    return BillingVerifyResponse(pro=True, entitlement=token,
                                 plan=result.plan, expiry=result.expiry_iso)
# --------------------------------------------------------------------------- #
#  Blocklist locale signée (GET /v1/local-blocklist) -- Paquet 8
# --------------------------------------------------------------------------- #
@router.get("/v1/local-blocklist", tags=["blocklist"])
async def local_blocklist_route() -> Response:
    """
    Renvoie la blocklist signée hybride Ed25519 + ML-DSA-65.
    Le fichier est régénéré quotidiennement par /opt/blokqr/blocklist/build.py
    (cron 4h00 UTC) et monté en LECTURE SEULE dans le conteneur via
    docker-compose.yml. Le serveur backend NE PEUT PAS modifier ce fichier
    (conteneur read-only + volume RO).
    Le client Android :
      1. Vérifie la signature hybride (réutilise le même code que pour les
         verdicts, mêmes clés).
      2. Vérifie que `expires_at` n'est pas dépassé.
      3. Stocke en cache local.
      4. Replanifie un refresh dans 24h (WorkManager).
    Cache HTTP : 12h. Le client peut interroger plus souvent, mais Caddy/
    nginx servira la version cachée pour économiser des cycles.
    Codes de retour :
      200 OK    : blocklist disponible
      503       : fichier pas encore généré (premier déploiement ou erreur cron)
    """
    blocklist_path = "/app/data/blocklist.json"
    if not os.path.exists(blocklist_path):
        # Le cron n'a pas encore généré le fichier (premier déploiement, ou
        # erreur). Le client repasse en mode bundled, c'est OK.
        return Response(
            content='{"detail":"blocklist_not_generated_yet"}',
            status_code=503,
            media_type="application/json",
            headers={"Cache-Control": "no-store"},
        )
    # Le fichier existe : on le sert tel quel avec cache HTTP 12h.
    try:
        with open(blocklist_path, "rb") as f:
            payload = f.read()
    except OSError as exc:
        logger.error("Lecture blocklist.json echouee : %s", exc)
        return Response(
            content='{"detail":"blocklist_read_error"}',
            status_code=503,
            media_type="application/json",
            headers={"Cache-Control": "no-store"},
        )
    return Response(
        content=payload,
        media_type="application/json",
        headers={
            "Cache-Control": "public, max-age=43200",
            "X-Content-Type-Options": "nosniff",
        },
    )
