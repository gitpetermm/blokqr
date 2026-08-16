"""
Réputation respectueuse de la vie privée par recherche de préfixe de hash
(k-anonymat).

Le client envoie uniquement des préfixes de 4 octets (issus du hachage SHA-256
des expressions hôte/chemin de l'URL normalisée). Le serveur renvoie tous les
hashs complets connus comme malveillants qui partagent l'un de ces préfixes.
Le client effectue ensuite la correspondance finale EN LOCAL.

Conséquence : le serveur n'apprend jamais quelle URL est vérifiée (un préfixe
de 4 octets est partagé par un nombre considérable d'URL). Couplé à un relais
OHTTP (RFC 9458), l'IP du client n'est pas non plus révélée.

La base de réputation locale ne contient que des HASHS, jamais d'URL en clair.
Elle est alimentable depuis des flux (URLhaus, PhishTank, listes internes) via
`load_threat_hashes`. Une amorce de démonstration est fournie.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Set

from app.security.url_normalize import HASH_PREFIX_BYTES, full_expression_hash


@dataclass
class ThreatHashEntry:
    """Une entrée de la base de réputation (hash complet -> métadonnées)."""

    full_hash_hex: str
    categories: List[str]
    source: str


class ReputationStore:
    """Index en mémoire des hashs malveillants, interrogeable par préfixe.

    Structure : prefix_hex (4 octets) -> liste d'entrées dont le hash complet
    commence par ce préfixe.
    """

    def __init__(self) -> None:
        self._by_prefix: Dict[str, List[ThreatHashEntry]] = {}
        self._full: Set[str] = set()

    @property
    def size(self) -> int:
        return len(self._full)

    def add_full_hash(self, full_hash_hex: str, categories: List[str], source: str) -> None:
        full_hash_hex = full_hash_hex.lower()
        if full_hash_hex in self._full:
            return
        self._full.add(full_hash_hex)
        prefix = full_hash_hex[: HASH_PREFIX_BYTES * 2]  # 2 hex chars par octet
        self._by_prefix.setdefault(prefix, []).append(
            ThreatHashEntry(full_hash_hex=full_hash_hex, categories=categories, source=source)
        )

    def add_expression(self, expression: str, categories: List[str], source: str) -> None:
        """Ajoute une expression hôte/chemin (hachée en interne)."""
        self.add_full_hash(full_expression_hash(expression), categories, source)

    def search_prefixes(self, prefixes: List[str]) -> List[ThreatHashEntry]:
        """Renvoie les entrées dont le préfixe correspond à l'un des préfixes fournis."""
        out: List[ThreatHashEntry] = []
        seen: Set[str] = set()
        for p in prefixes:
            p = p.lower()
            for entry in self._by_prefix.get(p, []):
                if entry.full_hash_hex not in seen:
                    seen.add(entry.full_hash_hex)
                    out.append(entry)
        return out


def load_threat_hashes(store: ReputationStore, feed_path: str = "") -> None:
    """Amorce la base via les adaptateurs de flux (échantillon embarqué + flux).

    En production : brancher la passerelle Safe Browsing v5 (OHTTP, côté client)
    et agréger URLhaus / PhishTank / listes internes ici (côté serveur).
    """
    # Import différé pour éviter une dépendance circulaire avec feeds.py.
    from app.analyzers.feeds import bootstrap
    bootstrap(store, feed_path)


# Instance unique partagée par le processus.
_STORE: ReputationStore | None = None


def get_store() -> ReputationStore:
    global _STORE
    if _STORE is None:
        _STORE = ReputationStore()
        load_threat_hashes(_STORE)
    return _STORE
