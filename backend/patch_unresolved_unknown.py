#!/usr/bin/env python3
# -*- coding: ascii -*-
"""
Micro-patch BlokQR : un domaine injoignable (signal 'domain_unresolved') rend un
verdict PRUDENT (UNKNOWN) au lieu de SAFE, conformement a la politique fail-closed
("jamais SAFE sur une analyse incomplete"). Ajoute aussi 'unresolved_redirect'.
Idempotent, abort si ancre absente, AST validee. Lancer depuis /opt/blokqr/backend.
"""
import ast, base64, os, sys
def d(s): return base64.b64decode(s).decode("utf-8")
PATH = "app/pipeline.py"
OLD_B64 = "ICAgICAgICBvciBhbnkocy5jb2RlID09ICJkeW5hbWljX2Vycm9yIiBmb3IgcyBpbiBzaWduYWxzKQ=="
NEW_B64 = "ICAgICAgICBvciBhbnkocy5jb2RlIGluICgiZHluYW1pY19lcnJvciIsICJ1bnJlc29sdmVkX3JlZGlyZWN0IiwgImRvbWFpbl91bnJlc29sdmVkIikgZm9yIHMgaW4gc2lnbmFscyk="
def main():
    if not os.path.exists(PATH):
        print("[ABORT] %s introuvable" % PATH); sys.exit(1)
    src = open(PATH, encoding="utf-8").read()
    old, new = d(OLD_B64), d(NEW_B64)
    if new in src:
        print("[SKIP] deja applique"); return
    if old not in src:
        print("[ABORT] ancre introuvable -> aucune ecriture"); sys.exit(1)
    src2 = src.replace(old, new, 1)
    ast.parse(src2)
    open(PATH, "w", encoding="utf-8").write(src2)
    print("[OK] verdict prudent (UNKNOWN) pour domaine injoignable. Rebuild requis.")
if __name__ == "__main__":
    main()
