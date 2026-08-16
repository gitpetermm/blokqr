#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Patch chirurgical de app/api/routes.py pour activer la vérification Play
Integrity sur POST /v1/install.

Effectue 2 modifications :
  1. Ajoute les imports manquants (logger, play_integrity) si absents.
  2. Remplace la fonction `install_route` par la version qui lit le header
     X-Integrity-Token, vérifie le token via play_integrity.verify(), et
     applique la politique permissif/strict.

Sûr :
  - Sauvegarde .bak_integrity avant modification.
  - Idempotent : relancer ne casse rien.
  - Valide la syntaxe Python (compile) après ; en cas d'erreur, restaure
    automatiquement la sauvegarde.

Usage :
    python patch_routes_for_integrity.py [chemin_routes.py]
"""
import os
import re
import shutil
import sys


NEW_INSTALL_ROUTE = '''# --------------------------------------------------------------------------- #
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
'''


def find_routes_file(arg: str | None) -> str:
    if arg and os.path.isfile(arg):
        return os.path.abspath(arg)
    candidates = [
        "app/api/routes.py",
        "/opt/blokqr/backend/app/api/routes.py",
    ]
    for c in candidates:
        if os.path.isfile(c):
            return os.path.abspath(c)
    print("ERREUR : impossible de trouver routes.py.")
    print("Lance depuis /opt/blokqr/backend ou passe le chemin en argument.")
    sys.exit(1)


def ensure_imports(src: str) -> tuple[str, list[str]]:
    """Garantit que `import logging`, `logger`, et `play_integrity` sont importés.

    On insère APRÈS le dernier `from app...` existant (ou après le dernier
    `import` si pas de from app). Préserve l'ordre original.
    """
    changes: list[str] = []

    # 1. import logging
    if not re.search(r"^import logging\b", src, re.M):
        # Insertion avant la première section non-import : ajout juste après
        # le bloc d'imports du haut.
        m = re.search(r"^(import [^\n]+|from [^\n]+)\n", src, re.M)
        if m:
            src = src.replace(
                m.group(0),
                m.group(0) + "import logging\n",
                1,
            )
            changes.append("import logging ajouté")

    # 2. logger = logging.getLogger(__name__) (au niveau module)
    if not re.search(r"^logger\s*=\s*logging\.getLogger", src, re.M):
        # Insertion juste après le dernier import.
        last_import = list(re.finditer(r"^(import [^\n]+|from [^\n]+)\n", src, re.M))
        if last_import:
            pos = last_import[-1].end()
            src = src[:pos] + "\nlogger = logging.getLogger(__name__)\n" + src[pos:]
            changes.append("logger ajouté")

    # 3. from app.security import play_integrity
    if "play_integrity" not in src:
        # Cherche le dernier from app... pour mettre à côté.
        last_app = list(re.finditer(r"^from app[^\n]+\n", src, re.M))
        if last_app:
            pos = last_app[-1].end()
            src = src[:pos] + "from app.security import play_integrity\n" + src[pos:]
            changes.append("from app.security import play_integrity ajouté")
        else:
            # fallback : ajoute en haut du fichier.
            src = "from app.security import play_integrity\n" + src
            changes.append("from app.security import play_integrity ajouté (haut)")

    # 4. S'assurer que Request est importé depuis fastapi.
    if not re.search(r"from fastapi import [^\n]*\bRequest\b", src):
        # Trouver le from fastapi import existant et ajouter Request à la liste.
        m = re.search(r"from fastapi import ([^\n]+)\n", src)
        if m:
            existing = m.group(1)
            if "Request" not in existing:
                # Préserve l'ordre, ajoute Request à la fin alphabétique.
                items = [i.strip() for i in existing.split(",")]
                items.append("Request")
                # Dédoublonne en gardant l'ordre.
                seen, dedup = set(), []
                for i in items:
                    if i not in seen:
                        seen.add(i)
                        dedup.append(i)
                new_line = f"from fastapi import {', '.join(dedup)}\n"
                src = src.replace(m.group(0), new_line, 1)
                changes.append("Request ajouté à l'import fastapi")
        else:
            # Pas d'import fastapi détecté (très improbable).
            pass

    # 5. S'assurer que HTTPException est importé depuis fastapi.
    if not re.search(r"from fastapi import [^\n]*\bHTTPException\b", src):
        m = re.search(r"from fastapi import ([^\n]+)\n", src)
        if m:
            existing = m.group(1)
            if "HTTPException" not in existing:
                items = [i.strip() for i in existing.split(",")]
                items.append("HTTPException")
                seen, dedup = set(), []
                for i in items:
                    if i not in seen:
                        seen.add(i)
                        dedup.append(i)
                new_line = f"from fastapi import {', '.join(dedup)}\n"
                src = src.replace(m.group(0), new_line, 1)
                changes.append("HTTPException ajouté à l'import fastapi")

    return src, changes


def replace_install_route(src: str) -> tuple[str, bool]:
    """Remplace la fonction install_route ET son bloc de commentaire d'en-tête.

    On capture depuis le bloc commentaire `---...Provisionnement...---` (s'il
    existe) jusqu'à la prochaine séparation visuelle (autre bloc ---, autre
    @router.post/get, ou EOF). Cela évite tout doublon de commentaire.
    """
    # Étape 1 : localiser la définition `async def install_route(`.
    func_match = re.search(
        r"^async def install_route\(",
        src, re.M,
    )
    if not func_match:
        return src, False

    # Étape 2 : remonter pour absorber le décorateur @router.post + le bloc
    # commentaire (lignes commençant par '#') qui le précèdent.
    func_start = func_match.start()
    lines_before = src[:func_start].splitlines(keepends=True)
    start_idx = len(lines_before)
    while start_idx > 0:
        prev = lines_before[start_idx - 1].rstrip("\n")
        stripped = prev.strip()
        if stripped == "":
            # ligne vide : accepter si la ligne au-dessus reste un commentaire
            # (cas: ligne vide DANS un bloc de commentaires sectionnels).
            if start_idx >= 2 and lines_before[start_idx - 2].strip().startswith("#"):
                start_idx -= 1
                continue
            else:
                break
        if stripped.startswith("@router."):
            start_idx -= 1
            continue
        if stripped.startswith("#"):
            start_idx -= 1
            continue
        # Ligne de code ou autre : on s'arrête.
        break
    block_start = sum(len(l) for l in lines_before[:start_idx])

    # Étape 3 : trouver la fin du bloc (jusqu'à la prochaine définition de
    # niveau module).
    end_match = re.search(
        r"\n(?=@router\.|async def |^def |# *-{2,})",
        src[func_start:],
        re.M,
    )
    if end_match:
        block_end = func_start + end_match.start() + 1  # +1 pour inclure le \n
    else:
        block_end = len(src)

    # Étape 4 : remplacer.
    return src[:block_start] + NEW_INSTALL_ROUTE + src[block_end:], True


def validate_python(path: str) -> bool:
    try:
        with open(path, "r", encoding="utf-8") as f:
            compile(f.read(), path, "exec")
        return True
    except SyntaxError as e:
        print(f"  ✗ ERREUR de syntaxe : {e}")
        return False


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else None
    path = find_routes_file(arg)
    print(f"Fichier ciblé : {path}")

    # Sauvegarde
    bak = path + ".bak_integrity"
    if not os.path.exists(bak):
        shutil.copy2(path, bak)
        print(f"Sauvegarde créée : {bak}")
    else:
        print(f"Sauvegarde déjà présente : {bak}")

    with open(path, "r", encoding="utf-8") as f:
        src = f.read()

    # 1. Imports
    src, import_changes = ensure_imports(src)
    for c in import_changes:
        print(f"  - {c}")
    if not import_changes:
        print("  - imports déjà à jour (aucune modification)")

    # 2. install_route
    src2, replaced = replace_install_route(src)
    if replaced:
        print("  - install_route remplacée")
        src = src2
    else:
        print("  ⚠ install_route introuvable, aucun remplacement effectué")

    # Écriture
    with open(path, "w", encoding="utf-8") as f:
        f.write(src)

    # Validation
    if not validate_python(path):
        shutil.copy2(bak, path)
        print("  -> restauré depuis la sauvegarde (aucune modification appliquée).")
        sys.exit(1)

    # Vérifications post
    with open(path, encoding="utf-8") as f:
        final = f.read()
    must_have = [
        "play_integrity.verify",
        "x-integrity-token",
        "require_integrity",
        "issue_install",
        "InstallResponse",
        "logger.info",
        "logger.warning",
    ]
    missing = [m for m in must_have if m not in final]
    if missing:
        print(f"  ⚠ Manque encore : {missing}")
        sys.exit(1)
    print("✓ routes.py patché et syntaxe Python valide.")


if __name__ == "__main__":
    main()
