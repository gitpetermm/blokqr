#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Retrait propre de VirusTotal + urlscan.io de BlokQR (licences non-commerciales).

Modifie :
  - app/analyzers/threat_intel.py : supprime _virustotal(), _urlscan(),
    le helper _vt_url_id(), leurs 2 appels dans gather_threat_intel(),
    et nettoie la docstring d'en-tête.
  - app/config.py : supprime virustotal_api_key et urlscan_api_key.

Sûr :
  - Crée des sauvegardes .bak_vtremoval avant toute modification.
  - Idempotent : si VT/urlscan sont déjà retirés, ne fait rien.
  - Valide la syntaxe Python (compile) après modification ; en cas d'erreur,
    restaure automatiquement la sauvegarde.

Usage :
    cd /opt/blokqr/backend
    python app/analyzers/remove_vt_urlscan.py
    (ou : python remove_vt_urlscan.py depuis n'importe où, en passant la racine)
"""
import ast
import os
import re
import shutil
import sys


def find_backend_root() -> str:
    """Localise la racine backend (contenant app/analyzers/threat_intel.py)."""
    candidates = [
        ".",
        "/opt/blokqr/backend",
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    ]
    for c in candidates:
        if os.path.isfile(os.path.join(c, "app", "analyzers", "threat_intel.py")):
            return os.path.abspath(c)
    print("ERREUR : impossible de localiser app/analyzers/threat_intel.py")
    print("Lance depuis /opt/blokqr/backend, ou passe la racine en argument.")
    sys.exit(1)


def remove_function(source: str, func_name: str) -> tuple[str, bool]:
    """
    Supprime une fonction (sync ou async) de haut niveau par son nom, en
    s'appuyant sur l'AST pour trouver ses bornes de lignes exactes.
    Retourne (nouveau_source, modifié?).
    """
    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        print(f"  ⚠ Source non parsable avant retrait de {func_name}: {e}")
        return source, False

    target = None
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == func_name:
            target = node
            break
    if target is None:
        return source, False  # déjà absent -> idempotent

    lines = source.splitlines(keepends=True)
    start = target.lineno - 1  # 0-indexed
    end = target.end_lineno     # exclusif en 0-indexed+1

    # Étendre en arrière pour absorber d'éventuels commentaires/décorateurs
    # directement accolés au-dessus de la def (lignes non vides commençant par #
    # ou @). On s'arrête à la première ligne vide rencontrée vers le haut.
    while start - 1 >= 0:
        prev = lines[start - 1].strip()
        if prev.startswith("@") or prev.startswith("#"):
            start -= 1
        else:
            break

    # Absorber les lignes vides juste après la fonction (pour ne pas laisser
    # 3 lignes blanches).
    while end < len(lines) and lines[end].strip() == "":
        end += 1

    new_lines = lines[:start] + lines[end:]
    return "".join(new_lines), True


def patch_threat_intel(path: str) -> int:
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    changed = 0

    # 1. Supprimer les 3 fonctions liées à VT/urlscan.
    for fn in ("_virustotal", "_urlscan", "_vt_url_id"):
        src, did = remove_function(src, fn)
        if did:
            changed += 1
            print(f"  - fonction {fn}() supprimée")

    # 2. Retirer les appels dans la liste `tasks = [...]`.
    for call in ("_virustotal(client, url, settings),", "_urlscan(client, url, settings),"):
        pattern = re.compile(r"^[ \t]*" + re.escape(call) + r"[ \t]*\r?\n", re.M)
        src, n = pattern.subn("", src)
        if n:
            changed += 1
            print(f"  - appel {call.split('(')[0]} retiré de gather()")

    # 3. Nettoyer la docstring d'en-tête (lignes VT + urlscan).
    #    On retire les 2 puces de la liste « Sources prises en charge ».
    doc_vt = re.compile(r"^[ \t]*-[ \t]*VirusTotal v3 \(clé requise\)\r?\n", re.M)
    src, n = doc_vt.subn("", src)
    if n:
        changed += 1
        print("  - ligne docstring VirusTotal retirée")

    # urlscan dans la docstring s'étale sur 2 lignes (continuation indentée).
    doc_urlscan = re.compile(
        r"^[ \t]*-[ \t]*urlscan\.io \(clé recommandée.*?\r?\n"
        r"[ \t]*publique anonyme sinon, fortement rate-limitée\)\r?\n",
        re.M | re.S,
    )
    src, n = doc_urlscan.subn("", src)
    if n:
        changed += 1
        print("  - lignes docstring urlscan retirées")

    # 4. Retirer l'import base64 s'il n'est plus utilisé (il ne servait qu'à VT).
    if "base64." not in src:
        src, n = re.subn(r"^import base64\r?\n", "", src, flags=re.M)
        if n:
            changed += 1
            print("  - import base64 (inutilisé) retiré")

    if changed:
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
    return changed


def patch_config(path: str) -> int:
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    changed = 0
    for key in ("virustotal_api_key", "urlscan_api_key"):
        # Ligne type : virustotal_api_key: str = Field(default="")
        pattern = re.compile(r"^[ \t]*" + re.escape(key) + r"\s*:.*\r?\n", re.M)
        src, n = pattern.subn("", src)
        if n:
            changed += 1
            print(f"  - config: {key} retiré")
    if changed:
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
    return changed


def validate_python(path: str) -> bool:
    """Vérifie que le fichier compile (syntaxe valide)."""
    try:
        with open(path, "r", encoding="utf-8") as f:
            compile(f.read(), path, "exec")
        return True
    except SyntaxError as e:
        print(f"  ✗ ERREUR de syntaxe dans {path}: {e}")
        return False


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else find_backend_root()
    ti_path = os.path.join(root, "app", "analyzers", "threat_intel.py")
    cfg_path = os.path.join(root, "app", "config.py")

    for p in (ti_path, cfg_path):
        if not os.path.isfile(p):
            print(f"ERREUR : fichier introuvable : {p}")
            sys.exit(1)

    # Sauvegardes
    for p in (ti_path, cfg_path):
        bak = p + ".bak_vtremoval"
        if not os.path.exists(bak):
            shutil.copy2(p, bak)
            print(f"Sauvegarde : {bak}")

    print("\n== threat_intel.py ==")
    n1 = patch_threat_intel(ti_path)
    if not validate_python(ti_path):
        shutil.copy2(ti_path + ".bak_vtremoval", ti_path)
        print("  -> restauré depuis la sauvegarde (aucune modification appliquée).")
        sys.exit(1)

    print("\n== config.py ==")
    n2 = patch_config(cfg_path)
    if not validate_python(cfg_path):
        shutil.copy2(cfg_path + ".bak_vtremoval", cfg_path)
        print("  -> restauré depuis la sauvegarde (aucune modification appliquée).")
        sys.exit(1)

    # Vérifications post-retrait
    with open(ti_path, encoding="utf-8") as f:
        ti = f.read()
    problems = []
    for needle in ("_virustotal", "_urlscan", "virustotal_api_key", "urlscan_api_key", "_vt_url_id"):
        if needle in ti:
            problems.append(f"threat_intel.py contient encore '{needle}'")
    # Les 4 providers restants doivent toujours être appelés.
    for keep in ("_web_risk(", "_phishtank(", "_urlhaus(", "_abuseipdb("):
        if keep not in ti:
            problems.append(f"PROVIDER MANQUANT : {keep} absent de gather() !")

    print(f"\nTotal : {n1 + n2} modification(s).")
    if problems:
        print("\n⚠ PROBLÈMES :")
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print("✓ VT + urlscan retirés. Les 4 providers (Web Risk, PhishTank, URLhaus, AbuseIPDB) sont préservés.")
    if n1 + n2 == 0:
        print("(Rien à faire — déjà retirés. Script idempotent.)")


if __name__ == "__main__":
    main()
