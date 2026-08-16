"""
Pipeline d'analyse : orchestration de bout en bout.

Enchaîne classification -> analyse lexicale -> résolution des redirections
-> analyse dynamique -> threat intelligence -> notation -> signature.

Pour les payloads non-URL (Wi-Fi, vCard, deep link, etc.), seules les étapes
pertinentes sont exécutées et des signaux spécifiques sont émis.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
from datetime import datetime, timedelta, timezone
from typing import List

from app.analyzers import lexical
from app.analyzers.capability_url import assess_capability, capability_signal
from app.analyzers.context_analyzer import (
    analyze_context, current_destination_hash, get_consensus_store,
)
from app.analyzers.domain_intel import analyze_domain
from app.analyzers.dynamic_sandbox import analyze_dynamic
from app.analyzers.payload_classifier import classify
from app.analyzers.redirect_resolver import resolve_chain
from app.analyzers.threat_intel import gather_threat_intel
from app.intel.ai_text import ai_analyze
from app.config import Settings
from app.schemas import (
    AnalysisReport,
    AnalyzeRequest,
    PayloadType,
    Severity,
    Signal,
    SignedVerdict,
    Verdict,
)
from app.scoring.engine import compute_score
from app.security.signing import VerdictSigner


def _analyze_non_url(classified, signals: List[Signal]) -> None:
    """Ajoute des signaux pour les payloads non navigables."""
    t = classified.payload_type
    if t == PayloadType.WIFI:
        if classified.extras.get("auth", "").upper() in ("", "NOPASS"):
            signals.append(Signal(
                code="open_wifi",
                title="Réseau Wi-Fi ouvert",
                detail="Le QR connecte à un réseau sans chiffrement : trafic interceptable.",
                severity=Severity.MEDIUM, weight=15, source="payload",
            ))
        if classified.extras.get("hidden", "").lower() == "true":
            signals.append(Signal(
                code="hidden_wifi",
                title="Réseau Wi-Fi masqué",
                detail="SSID caché : prudence, peut imiter un réseau de confiance.",
                severity=Severity.LOW, weight=6, source="payload",
            ))
    elif t == PayloadType.DEEP_LINK:
        signals.append(Signal(
            code="deep_link",
            title="Lien applicatif (deep link)",
            detail=(
                "Ce code déclenche une action dans une application "
                f"(schéma « {classified.scheme} »). Les deep links peuvent "
                "lancer des opérations sensibles ; n'ouvrez que si vous attendez cette action."
            ),
            severity=Severity.MEDIUM, weight=14, source="payload",
        ))
    elif t == PayloadType.CRYPTO:
        signals.append(Signal(
            code="crypto_payment",
            title="Demande de paiement en cryptomonnaie",
            detail="Adresse de paiement irréversible : vérifiez le destinataire.",
            severity=Severity.MEDIUM, weight=12, source="payload",
        ))
    elif t == PayloadType.SMS:
        signals.append(Signal(
            code="sms_action",
            title="Envoi de SMS pré-rempli",
            detail="Peut déclencher un SMS surtaxé. Vérifiez le numéro.",
            severity=Severity.LOW, weight=8, source="payload",
        ))


def _report_sha256(report: AnalysisReport) -> str:
    """SHA-256 du rapport complet (lie capture + raisons à la signature)."""
    return hashlib.sha256(report.model_dump_json().encode("utf-8")).hexdigest()


def _report_canonical(report: AnalysisReport) -> str:
    """Chaine canonique EXACTE du rapport : hachee pour report_sha256 ET renvoyee telle quelle au client."""
    return report.model_dump_json()


def _build_canonical(verdict: Verdict, score: int, report_sha256: str,
                     nonce: str, issued_at: str, expires_at: str, key_id: str) -> str:
    """Chaîne canonique déterministe signée (le client la reconstruit à l'identique)."""
    obj = {
        "v": verdict.value,
        "score": score,
        "report_sha256": report_sha256,
        "nonce": nonce,
        "issued_at": issued_at,
        "expires_at": expires_at,
        "key_id": key_id,
    }
    return json.dumps(obj, separators=(",", ":"), sort_keys=True, ensure_ascii=False)


def _build_reasons(verdict: Verdict, report: AnalysisReport,
                   signals: List[Signal]) -> List[str]:
    """Construit des explications en langage clair pour la double prévisualisation.

    On retient les signaux les plus sévères, formulés simplement, pour
    répondre à la question « pourquoi ce verdict ? ».
    """
    order = {Severity.CRITICAL: 0, Severity.HIGH: 1, Severity.MEDIUM: 2,
             Severity.LOW: 3, Severity.INFO: 4}
    ranked = sorted(
        [s for s in signals if s.severity != Severity.INFO and s.title],
        key=lambda s: (order.get(s.severity, 9), -s.weight),
    )
    reasons = [s.title for s in ranked[:5]]
    if not reasons:
        if verdict == Verdict.SAFE:
            reasons = ["Aucun signal de menace détecté lors de l'analyse."]
    return reasons

# Sévérités « suspectes mais non conclusives » qui peuvent déclencher l'étage IA.
_AI_SUSPECT_SEV = {Severity.MEDIUM, Severity.HIGH}


def _ai_stage_applies(report: AnalysisReport, signals: List[Signal]) -> bool:
    """Cas AMBIGU : la threat intel ne liste rien ET un signal reste douteux,
    sans preuve conclusive (aucun CRITICAL). C'est l'angle mort de Web Risk."""
    intel_clean = not any(ti.malicious for ti in report.threat_intel)
    already_conclusive = any(s.severity == Severity.CRITICAL for s in signals)
    suspicion = (
        bool(report.login_page_impersonation)
        or report.cloaking_detected
        or report.gating_detected
        or any(s.severity in _AI_SUSPECT_SEV for s in signals)
    )
    return intel_clean and not already_conclusive and suspicion
def build_timeout_verdict(
    request: AnalyzeRequest, settings: Settings, signer: VerdictSigner
) -> SignedVerdict:
    """Verdict de repli SIGNÉ lorsque l'analyse dépasse le budget global.

    Politique fail-closed : on ne peut pas affirmer « safe » sans avoir analysé,
    donc on renvoie UNKNOWN. Le verdict reste signé (comme un verdict normal)
    pour que le client l'accepte et applique sa politique de prudence.
    """
    classified = classify(request.raw_payload)
    report = AnalysisReport(
        payload_type=classified.payload_type,
        displayed_value=classified.normalized,
    )
    if classified.payload_type == PayloadType.URL and classified.url:
        report.original_target = classified.url
        report.displayed_value = classified.url
    report.signals = [Signal(
        code="analysis_timeout",
        title="Analyse incomplète (délai dépassé)",
        detail="Le service n'a pas pu terminer l'analyse dans le temps imparti.",
        severity=Severity.INFO, weight=0, source="pipeline",
    )]
    report.reasons = ["Analyse incomplète : par prudence, l'ouverture n'est pas recommandée."]

    verdict = Verdict.UNKNOWN
    score = 0
    now = datetime.now(timezone.utc)
    issued_at = now.isoformat()
    expires_at = (now + timedelta(seconds=settings.verdict_ttl_seconds)).isoformat()
    report_canonical = _report_canonical(report)
    report_hash = hashlib.sha256(report_canonical.encode("utf-8")).hexdigest()
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
        report_canonical=report_canonical,
        canonical_payload=canonical,
        signature_ed25519_b64=bundle.ed25519_b64,
        signature_mldsa65_b64=bundle.mldsa65_b64,
        public_key_ed25519_b64=bundle.public_key_ed25519_b64,
        key_id=bundle.key_id,
    )
async def analyze(
    request: AnalyzeRequest, settings: Settings, signer: VerdictSigner,
    deep: bool = False,
) -> SignedVerdict:
    """Exécute le pipeline complet et renvoie un verdict signé."""
    classified = classify(request.raw_payload)
    signals: List[Signal] = []

    report = AnalysisReport(
        payload_type=classified.payload_type,
        displayed_value=classified.normalized,
    )

    if classified.payload_type in (PayloadType.URL,) and classified.url:
        report.original_target = classified.url

        # 1. Lexical (hors-ligne, rapide).
        signals.extend(lexical.analyze_url(classified.url))

        # 2. Capability-URL : ne pas exposer un lien personnel au palier profond
        #    sans consentement explicite (correctif de fuite de vie privée).
        cap = assess_capability(classified.url)
        report.capability_url = cap.is_capability
        privacy_hold = cap.is_capability and not request.consent_deep_analysis
        report.privacy_hold = privacy_hold
        cap_sig = capability_signal(classified.url)
        if cap_sig:
            signals.append(cap_sig)

        # 3. Résolution des redirections (réseau, sans JS).
        hops, final_url, redir_signals = await resolve_chain(classified.url, settings)
        report.redirect_chain = hops
        report.final_target = final_url
        report.displayed_value = final_url or classified.url
        signals.extend(redir_signals)

        # 4. Lexical sur la destination finale + intelligence de DOMAINE
        #    (détection AiTM/sosie, efficace là où l'analyse visuelle échoue).
        target = report.final_target or classified.url
        if final_url and final_url != classified.url:
            for s in lexical.analyze_url(final_url):
                s.code = f"final_{s.code}"
                s.title = f"[Destination finale] {s.title}"
                signals.append(s)
        dom = analyze_domain(target)
        report.domain_registrable = dom.registrable
        if dom.signals:
            signals.extend(dom.signals)
        if dom.impersonated_brand and not report.login_page_impersonation:
            report.login_page_impersonation = dom.impersonated_brand

        # 5+6. Analyse dynamique (rendu) + threat intelligence, EN PARALLÈLE et
        #      l'étape dynamique BORNÉE par un budget de temps. Objectif :
        #      toujours répondre sous le délai réseau du client, même si la cible
        #      est lente/injoignable. Suspendues si privacy_hold.
        if not privacy_hold:
            intel_target = report.final_target or classified.url

            async def _run_dynamic():
                if not final_url:
                    return None
                try:
                    return await asyncio.wait_for(
                        analyze_dynamic(
                            final_url, settings,
                            want_screenshot=request.want_screenshot,
                        ),
                        timeout=settings.dynamic_budget_seconds,
                    )
                except asyncio.TimeoutError:
                    return None

            dyn, intel = await asyncio.gather(
                _run_dynamic(),
                gather_threat_intel(intel_target, settings),
            )
            report.threat_intel = intel

            if dyn is not None and dyn.available:
                report.cloaking_detected = dyn.cloaking_detected
                report.gating_detected = dyn.gating_detected
                if dyn.impersonated_brand:
                    report.login_page_impersonation = dyn.impersonated_brand
                report.screenshot_b64 = dyn.screenshot_b64
                signals.extend(dyn.signals)
                if dyn.final_url and dyn.final_url != final_url:
                    report.final_target = dyn.final_url
                    report.displayed_value = dyn.final_url
            elif final_url:
                # Rendu non abouti dans le budget imparti -> fail-closed.
                signals.append(Signal(
                    code="dynamic_error", title="Analyse dynamique incomplète",
                    detail="Le rendu dynamique a dépassé le budget de temps imparti.",
                    severity=Severity.INFO, weight=0, source="dynamic"))

        # 7. Contexte temporel / géographique + consensus communautaire.
        ctx_signals = analyze_context(
            report.final_target or classified.url,
            request.prior_destination_hash,
            source_hash=request.source_hash,
        )
        signals.extend(ctx_signals)
        report.destination_changed = any(s.code == "destination_changed" for s in ctx_signals)
        report.diverges_consensus = any(
            s.code == "destination_diverges_consensus" for s in ctx_signals)
        report.current_destination_hash = current_destination_hash(
            report.final_target or classified.url)

        # Contribution au consensus (k-anonyme) si la source est fournie.
        if request.source_hash and report.current_destination_hash:
            contributor = (request.client_nonce or "anon")[:16]
            get_consensus_store().contribute(
                request.source_hash, report.current_destination_hash, contributor)

        report.qrljacking_suspected = any(s.code == "qr_login_endpoint" for s in signals)
# 7b. Étage IA (Gemini) — 2e ligne, cas AMBIGUS uniquement. Métadonnées
        #     seules (URL, redirections, signaux du sandbox) ; jamais le contenu
        #     de page. Le Signal renvoyé entre dans compute_score comme les autres.
        if settings.enable_ai_stage and not privacy_hold and _ai_stage_applies(report, signals):
            ai_signal = await ai_analyze(
                final_url=report.final_target or classified.url,
                redirect_chain=[h.url for h in report.redirect_chain],
                host=report.domain_registrable or "",
                brand_hint=report.login_page_impersonation,
                cloaking=report.cloaking_detected,
                gating=report.gating_detected,
            )
            if ai_signal is not None:
                signals.append(ai_signal)
    else:
        _analyze_non_url(classified, signals)

    # 8. Notation et verdict.
    score, verdict, ti_signals = compute_score(signals, report.threat_intel, settings)
    signals.extend(ti_signals)

    # 8b. Politique fail-closed : ne jamais affirmer « safe » sur une analyse
    #     incomplète (analyse profonde suspendue, rendu indisponible, erreur).
    analysis_incomplete = (
        report.privacy_hold
        or any(s.code == "dynamic_error" for s in signals)
    )
    if settings.fail_closed and verdict == Verdict.SAFE and analysis_incomplete:
        verdict = Verdict.UNKNOWN

    report.signals = signals
    report.reasons = _build_reasons(verdict, report, signals)

    # 9. Signature du verdict (liée au hash du rapport complet).
    now = datetime.now(timezone.utc)
    issued_at = now.isoformat()
    expires_at = (now + timedelta(seconds=settings.verdict_ttl_seconds)).isoformat()
    report_canonical = _report_canonical(report)
    report_hash = hashlib.sha256(report_canonical.encode("utf-8")).hexdigest()
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
        report_canonical=report_canonical,
        canonical_payload=canonical,
        signature_ed25519_b64=bundle.ed25519_b64,
        signature_mldsa65_b64=bundle.mldsa65_b64,
        public_key_ed25519_b64=bundle.public_key_ed25519_b64,
        key_id=bundle.key_id,
    )
