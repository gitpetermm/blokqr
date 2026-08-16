"""
Moteur de notation et de décision.

Fusionne l'ensemble des signaux (lexicaux, redirections, dynamiques) et les
verdicts de threat intelligence en un score 0..100 et un verdict final.

Principes :
  - Un signal CRITICAL de threat intel (URL confirmée malveillante) force le
    verdict DANGEROUS, indépendamment du score additif.
  - Le score additif plafonne à 100.
  - Les seuils (suspicious / dangerous) sont configurables.
  - Politique fail-safe : en cas de données insuffisantes, on penche vers la
    prudence (jamais SAFE par défaut sur un type analysable non vérifié).
"""
from __future__ import annotations

from typing import List, Tuple

from app.config import Settings
from app.schemas import Severity, Signal, ThreatIntelResult, Verdict

# Bonus de score appliqué quand une source de réputation confirme la malveillance.
_TI_MALICIOUS_WEIGHT = 45


def compute_score(
    signals: List[Signal],
    threat_intel: List[ThreatIntelResult],
    settings: Settings,
) -> Tuple[int, Verdict, List[Signal]]:
    """Calcule (score, verdict, signaux_de_threat_intel_ajoutés)."""
    ti_signals: List[Signal] = []
    score = sum(max(0, s.weight) for s in signals)

    confirmed_malicious = False
    for ti in threat_intel:
        if ti.available and ti.malicious:
            confirmed_malicious = True
            ti_signals.append(Signal(
                code=f"ti_{ti.provider}",
                title=f"Réputation malveillante ({ti.provider})",
                detail=ti.detail or "Source de threat intelligence positive.",
                severity=Severity.CRITICAL,
                weight=_TI_MALICIOUS_WEIGHT,
                source="threat_intel",
            ))

    score += sum(s.weight for s in ti_signals)
    score = min(100, score)

    # Présence d'un signal CRITICAL local (ex. usurpation de login confirmée).
    has_critical = any(s.severity == Severity.CRITICAL for s in signals)

    # MALICIOUS = menace confirmée (réputation positive ou signal critique),
    # ou score atteignant le seuil haut.
    if confirmed_malicious or has_critical:
        verdict = Verdict.MALICIOUS
        score = max(score, settings.score_threshold_malicious)
    elif score >= settings.score_threshold_malicious:
        verdict = Verdict.MALICIOUS
    elif score >= settings.score_threshold_dangerous:
        verdict = Verdict.DANGEROUS
    else:
        verdict = Verdict.SAFE

    return score, verdict, ti_signals
