#!/usr/bin/env python3
# -*- coding: ascii -*-
"""
Patch correcteur BlokQR : cable le signal "age du domaine" dans l'unique
asyncio.gather du pipeline deploye (structure sans branche if deep/else).
Idempotent + abort si ancre absente + validation AST. Lancer depuis
/opt/blokqr/backend, APRES patch_domain_age.py (qui a deja cree le module,
ajoute la config et l'import).
"""
import ast, base64, os, sys

def d(s): return base64.b64decode(s).decode("utf-8")

PATH = "app/pipeline.py"
OLD_B64 = "ICAgICAgICAgICAgZHluLCBpbnRlbCA9IGF3YWl0IGFzeW5jaW8uZ2F0aGVyKAogICAgICAgICAgICAgICAgX3J1bl9keW5hbWljKCksCiAgICAgICAgICAgICAgICBnYXRoZXJfdGhyZWF0X2ludGVsKGludGVsX3RhcmdldCwgc2V0dGluZ3MpLAogICAgICAgICAgICApCiAgICAgICAgICAgIHJlcG9ydC50aHJlYXRfaW50ZWwgPSBpbnRlbAo="
NEW_B64 = "ICAgICAgICAgICAgZHluLCBpbnRlbCwgYWdlX3NpZyA9IGF3YWl0IGFzeW5jaW8uZ2F0aGVyKAogICAgICAgICAgICAgICAgX3J1bl9keW5hbWljKCksCiAgICAgICAgICAgICAgICBnYXRoZXJfdGhyZWF0X2ludGVsKGludGVsX3RhcmdldCwgc2V0dGluZ3MpLAogICAgICAgICAgICAgICAgZG9tYWluX2FnZV9zaWduYWwocmVwb3J0LmRvbWFpbl9yZWdpc3RyYWJsZSwgc2V0dGluZ3MpLAogICAgICAgICAgICApCiAgICAgICAgICAgIHJlcG9ydC50aHJlYXRfaW50ZWwgPSBpbnRlbAogICAgICAgICAgICBpZiBhZ2Vfc2lnOgogICAgICAgICAgICAgICAgc2lnbmFscy5hcHBlbmQoYWdlX3NpZykK"

def main():
    if not os.path.exists(PATH):
        print("[ABORT] %s introuvable (mauvais repertoire ?)" % PATH); sys.exit(1)
    src = open(PATH, encoding="utf-8").read()

    # Pre-requis : l'import doit etre present (ajoute par le 1er patch).
    if "from app.analyzers.domain_age import domain_age_signal" not in src:
        print("[ABORT] import domain_age_signal absent : relancez d'abord patch_domain_age.py")
        sys.exit(1)

    old, new = d(OLD_B64), d(NEW_B64)
    if new in src:
        print("[SKIP] %s : deja cable" % PATH); return
    if old not in src:
        print("[ABORT] %s : ancre introuvable -> aucune ecriture" % PATH); sys.exit(1)

    src2 = src.replace(old, new, 1)
    ast.parse(src2)  # validation avant ecriture
    with open(PATH, "w", encoding="utf-8") as f:
        f.write(src2)
    print("[OK]   %s : signal age du domaine cable" % PATH)
    print("\nTermine. Reconstruisez le conteneur.")

if __name__ == "__main__":
    main()
