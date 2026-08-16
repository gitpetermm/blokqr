#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Patch chirurgical de app/api/routes.py pour ajouter GET /v1/local-blocklist.

Ce patch :
  1. Ajoute les imports nécessaires (os, Response depuis fastapi).
  2. Ajoute la route GET /v1/local-blocklist en fin de fichier.

Idempotent : relancer ne casse rien. Crée une sauvegarde .bak_blocklist.

Usage :
    python3 patch_routes_for_blocklist.py [chemin_routes.py]
"""
import os
import re
import shutil
import sys


NEW_ROUTE_BLOCK = '''

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
'''


def find_routes_file(arg):
    if arg and os.path.isfile(arg):
        return os.path.abspath(arg)
    candidates = [
        "app/api/routes.py",
        "/opt/blokqr/backend/app/api/routes.py",
    ]
    for c in candidates:
        if os.path.isfile(c):
            return os.path.abspath(c)
    print("ERREUR : routes.py introuvable.", file=sys.stderr)
    sys.exit(1)


def ensure_imports(src):
    """Garantit que `Response` (depuis fastapi) et `os` sont importes."""
    changes = []

    # 1. import os (si absent)
    if not re.search(r"^import os\b", src, re.M):
        # Insertion apres le dernier import du haut
        last_import = list(re.finditer(r"^(import [^\n]+|from [^\n]+)\n", src, re.M))
        if last_import:
            pos = last_import[-1].end()
            src = src[:pos] + "import os\n" + src[pos:]
            changes.append("import os ajoute")

    # 2. Response depuis fastapi
    if not re.search(r"from fastapi import [^\n]*\bResponse\b", src):
        m = re.search(r"from fastapi import ([^\n]+)\n", src)
        if m:
            existing = m.group(1)
            if "Response" not in existing:
                items = [i.strip() for i in existing.split(",")]
                items.append("Response")
                # Dedup en preservant l'ordre
                seen, dedup = set(), []
                for i in items:
                    if i not in seen:
                        seen.add(i)
                        dedup.append(i)
                new_line = f"from fastapi import {', '.join(dedup)}\n"
                src = src.replace(m.group(0), new_line, 1)
                changes.append("Response ajoute a l'import fastapi")

    return src, changes


def append_route(src):
    """Ajoute la nouvelle route en fin de fichier si pas deja presente."""
    if "/v1/local-blocklist" in src:
        return src, False
    if not src.endswith("\n"):
        src += "\n"
    src += NEW_ROUTE_BLOCK
    return src, True


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else None
    path = find_routes_file(arg)
    print(f"Cible : {path}")

    bak = path + ".bak_blocklist"
    if not os.path.exists(bak):
        shutil.copy2(path, bak)
        print(f"Sauvegarde : {bak}")
    else:
        print(f"Sauvegarde existante : {bak}")

    with open(path, "r", encoding="utf-8") as f:
        src = f.read()

    src, import_changes = ensure_imports(src)
    for c in import_changes:
        print(f"  - {c}")
    if not import_changes:
        print("  - imports deja OK")

    src, added = append_route(src)
    if added:
        print("  - route /v1/local-blocklist ajoutee")
    else:
        print("  - route deja presente (idempotent)")

    with open(path, "w", encoding="utf-8") as f:
        f.write(src)

    # Validation syntaxique
    try:
        compile(open(path).read(), path, "exec")
        print("Syntaxe Python : VALIDE")
    except SyntaxError as e:
        print(f"ERREUR syntaxe : {e}")
        shutil.copy2(bak, path)
        print("Restaure depuis sauvegarde")
        sys.exit(1)


if __name__ == "__main__":
    main()
