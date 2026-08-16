"""
Adaptateurs de flux de réputation -> base k-anonyme.

Corrige la faiblesse « base de réputation vide » : le palier rapide n'était
alimenté que par 3 hashs de démonstration. Ce module ingère de vraies sources.

Stratégie de positionnement (recommandation de l'analyse critique) :
  - CONSOMMER Google Safe Browsing v5 (passerelle OHTTP) plutôt que de
    réinventer la réputation : préfixes de hash + IP masquée, gratuit en usage
    non commercial. C'est la source primaire recommandée côté client.
  - Côté serveur, agréger des flux ouverts (URLhaus, PhishTank) et des listes
    internes dans la base k-anonyme, pour enrichir et mutualiser.

Toutes les sources réseau nécessitent connectivité/clé : on fournit donc un
échantillon EMBARQUÉ pour un fonctionnement et des tests hors-ligne, plus des
fonctions d'ingestion prêtes à brancher sur les flux réels.
"""
from __future__ import annotations

import os
from typing import Iterable, List

from app.analyzers.reputation_kanon import ReputationStore
from app.security.url_normalize import _host_path_expressions, canonicalize

_SAMPLE_PATH = os.path.join(os.path.dirname(__file__), "..", "data",
                            "threat_feed_sample.txt")


def ingest_url(store: ReputationStore, url: str, categories: List[str],
               source: str) -> int:
    """Normalise une URL et ajoute toutes ses expressions hôte/chemin (hachées)."""
    try:
        norm = canonicalize(url if "://" in url else "http://" + url)
    except Exception:  # noqa: BLE001
        return 0
    n = 0
    for expr in _host_path_expressions(norm):
        store.add_expression(expr, categories, source)
        n += 1
    return n


def load_lines(store: ReputationStore, lines: Iterable[str],
               categories: List[str], source: str) -> int:
    total = 0
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        total += ingest_url(store, line, categories, source)
    return total


def load_sample(store: ReputationStore) -> int:
    if not os.path.exists(_SAMPLE_PATH):
        return 0
    with open(_SAMPLE_PATH, "r", encoding="utf-8") as f:
        return load_lines(store, f, ["phishing"], "sample")


def load_urlhaus_csv(store: ReputationStore, csv_text: str) -> int:
    """Ingestion du format CSV URLhaus (colonne `url`). À alimenter par un fetch."""
    total = 0
    for row in csv_text.splitlines():
        if row.startswith("#") or not row.strip():
            continue
        cols = [c.strip().strip('"') for c in row.split(",")]
        # URLhaus : id,dateadded,url,url_status,threat,...
        url = next((c for c in cols if c.startswith("http")), None)
        if url:
            total += ingest_url(store, url, ["malware", "urlhaus"], "urlhaus")
    return total


def bootstrap(store: ReputationStore, feed_path: str = "") -> int:
    """Amorce la base : échantillon embarqué + fichier de flux optionnel."""
    total = load_sample(store)
    if feed_path and os.path.exists(feed_path):
        with open(feed_path, "r", encoding="utf-8") as f:
            total += load_lines(store, f, ["phishing", "feed"], "feed_file")
    return total
