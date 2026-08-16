"""
Analyse contextuelle temporelle et géographique (version durcie).

Corrections apportées aux faiblesses identifiées :

  1. Faux positifs CDN/régionaux : on hache désormais le DOMAINE ENREGISTRABLE
     (eTLD+1) et non l'hôte complet. amazon.fr/.com ou les sous-domaines de CDN
     ne déclenchent plus de fausse alerte de changement.

  2. Aveuglement au premier scan : la mémoire locale ne protège pas la victime
     d'une attaque « campagne légitime puis détournée » (chaque victime est à son
     premier scan). On ajoute donc une COUCHE DE CONSENSUS COMMUNAUTAIRE
     k-anonyme : des appareils consentants contribuent (hash de source -> hash de
     destination, avec région et fenêtre temporelle grossières). Si la
     destination courante diverge du consensus établi par N contributeurs
     distincts, on alerte — même au premier scan de la victime.

Résistance à l'empoisonnement : le consensus n'est utilisé comme SIGNAL (pas
comme verdict) qu'au-delà d'un quorum de contributeurs distincts ; il pondère,
il ne décide pas.
"""
from __future__ import annotations

import hashlib
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set
from urllib.parse import urlsplit

from app.analyzers.domain_intel import registrable_domain
from app.schemas import Severity, Signal

_SOURCE = "context"
_CONSENSUS_QUORUM = 5  # contributeurs distincts requis avant d'utiliser le consensus


def _destination_token(final_url: str) -> str:
    reg = registrable_domain(urlsplit(final_url).hostname or "")
    return hashlib.sha256(("dst:" + reg).encode("utf-8")).hexdigest()


def current_destination_hash(final_url: Optional[str]) -> Optional[str]:
    if not final_url:
        return None
    return _destination_token(final_url)


# --------------------------------------------------------------------------- #
#  Consensus communautaire k-anonyme (en mémoire ; pluggable vers un store)
# --------------------------------------------------------------------------- #
@dataclass
class _SourceConsensus:
    # destination_hash -> ensemble de contributeurs distincts (hashés)
    dest_contributors: Dict[str, Set[str]] = field(default_factory=lambda: defaultdict(set))

    def add(self, dest_hash: str, contributor: str) -> None:
        self.dest_contributors[dest_hash].add(contributor)

    def dominant(self) -> Optional[str]:
        best, best_n = None, 0
        for d, contribs in self.dest_contributors.items():
            if len(contribs) > best_n:
                best, best_n = d, len(contribs)
        return best if best_n >= _CONSENSUS_QUORUM else None


class ConsensusStore:
    """Agrégat k-anonyme des couples (source -> destination) observés."""

    def __init__(self) -> None:
        self._by_source: Dict[str, _SourceConsensus] = defaultdict(_SourceConsensus)

    def contribute(self, source_hash: str, dest_hash: str, contributor_hash: str) -> None:
        self._by_source[source_hash].add(dest_hash, contributor_hash)

    def expected_destination(self, source_hash: str) -> Optional[str]:
        sc = self._by_source.get(source_hash)
        return sc.dominant() if sc else None


_STORE: Optional[ConsensusStore] = None


def get_consensus_store() -> ConsensusStore:
    global _STORE
    if _STORE is None:
        _STORE = ConsensusStore()
    return _STORE


# --------------------------------------------------------------------------- #
#  Analyse
# --------------------------------------------------------------------------- #
def analyze_context(
    final_url: Optional[str],
    prior_destination_hash: Optional[str],
    source_hash: Optional[str] = None,
) -> List[Signal]:
    signals: List[Signal] = []
    if not final_url:
        return signals

    current = _destination_token(final_url)

    # 1) Mémoire locale (fournie par l'appareil).
    if prior_destination_hash:
        if prior_destination_hash.lower() != current.lower():
            signals.append(Signal(
                code="destination_changed",
                title="QR dynamique : la destination a changé",
                detail="Ce code menait précédemment vers un autre domaine. "
                       "Un changement de cible dans le temps est une technique "
                       "d'hameçonnage différé.",
                severity=Severity.HIGH, weight=30, source=_SOURCE,
            ))
        else:
            signals.append(Signal(
                code="destination_stable", title="Destination cohérente dans le temps",
                detail="La destination correspond à celle observée précédemment.",
                severity=Severity.INFO, weight=0, source=_SOURCE,
            ))

    # 2) Consensus communautaire (protège dès le premier scan de la victime).
    if source_hash:
        expected = get_consensus_store().expected_destination(source_hash)
        if expected and expected != current:
            signals.append(Signal(
                code="destination_diverges_consensus",
                title="Destination divergente du consensus communautaire",
                detail="D'autres utilisateurs voient ce même code mener vers un "
                       "domaine différent. Forte présomption de QR dynamique "
                       "détourné ou de cloaking géographique.",
                severity=Severity.HIGH, weight=32, source=_SOURCE,
            ))

    return signals
