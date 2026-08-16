# -*- coding: utf-8 -*-
"""
BlokQR — Retrait de la collecte de localisation (géohash grossier) côté backend.

Supprime, de façon IDEMPOTENTE et sûre :
  * app/schemas.py            -> champ `coarse_geohash` (+ son commentaire)
  * app/pipeline.py           -> argument `request.coarse_geohash` dans l'appel analyze_context
  * app/analyzers/context_analyzer.py
        - paramètre `coarse_geohash` de la signature analyze_context()
        - bloc émettant le signal `geo_context_recorded`

Conçu pour être exécuté DEPUIS /opt/blokqr/backend :
    cd /opt/blokqr/backend
    python3 patch_remove_geohash.py

Aucune dépendance. N'écrit un fichier que s'il change, après contrôle de syntaxe.
Ancres ASCII uniquement (jamais de texte accentué matché) -> robuste à l'encodage.
"""
import ast
import io
import sys

# --------------------------------------------------------------------------- #
#  Transformations pures (texte -> texte). Testables sans toucher au disque.
# --------------------------------------------------------------------------- #
def transform_schemas(text):
    """Retire le champ coarse_geohash et son commentaire de tête."""
    lines = text.splitlines(keepends=True)
    out = []
    i = 0
    changed = False
    while i < len(lines):
        if lines[i].strip().startswith("coarse_geohash:"):
            # Retirer les commentaires (#...) qui précèdent immédiatement.
            while out and out[-1].strip().startswith("#"):
                out.pop()
            # Retirer le champ jusqu'à sa parenthèse fermante ')'.
            j = i
            while j < len(lines) and lines[j].strip() != ")":
                j += 1
            i = j + 1  # saute aussi la ligne ')'
            changed = True
            continue
        out.append(lines[i])
        i += 1
    return "".join(out), changed


def transform_pipeline(text):
    """Retire la ligne `request.coarse_geohash,` de l'appel analyze_context."""
    lines = text.splitlines(keepends=True)
    out = [ln for ln in lines if ln.strip() != "request.coarse_geohash,"]
    changed = len(out) != len(lines)
    return "".join(out), changed


def transform_context(text):
    """Retire le paramètre coarse_geohash + le bloc geo_context_recorded."""
    lines = text.splitlines(keepends=True)
    changed = False

    # 1) Paramètre de signature.
    tmp = [ln for ln in lines if ln.strip() != "coarse_geohash: Optional[str],"]
    if len(tmp) != len(lines):
        changed = True
    lines = tmp

    # 2) Bloc `if coarse_geohash:` ... `))` (+ ligne vide suivante).
    out = []
    i = 0
    while i < len(lines):
        if lines[i].strip().startswith("if coarse_geohash:"):
            j = i
            while j < len(lines) and lines[j].strip() != "))":
                j += 1
            i = j + 1  # saute la ligne '))'
            # Avale une éventuelle ligne vide laissée derrière.
            if i < len(lines) and lines[i].strip() == "":
                i += 1
            changed = True
            continue
        out.append(lines[i])
        i += 1
    return "".join(out), changed


TARGETS = [
    ("app/schemas.py", transform_schemas),
    ("app/pipeline.py", transform_pipeline),
    ("app/analyzers/context_analyzer.py", transform_context),
]


def main():
    any_error = False
    for path, fn in TARGETS:
        try:
            with io.open(path, "r", encoding="utf-8") as f:
                src = f.read()
        except FileNotFoundError:
            print("  [IGNORE] %s introuvable (rien a faire)" % path)
            continue

        new_src, changed = fn(src)

        if not changed:
            print("  [OK   ] %s : deja propre (idempotent)" % path)
            continue

        # Controle de syntaxe AVANT ecriture.
        try:
            ast.parse(new_src)
        except SyntaxError as e:
            print("  [ABORT] %s : syntaxe invalide apres patch (%s) -> NON ecrit" % (path, e))
            any_error = True
            continue

        with io.open(path, "w", encoding="utf-8") as f:
            f.write(new_src)
        print("  [PATCH] %s : localisation retiree" % path)

    if any_error:
        print("\n>>> Des erreurs sont survenues. Verifiez les messages [ABORT].")
        sys.exit(1)
    print("\n>>> Termine. Reconstruisez l'image puis redemarrez le conteneur.")


if __name__ == "__main__":
    main()
