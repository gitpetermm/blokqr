# -*- coding: utf-8 -*-
"""
BlokQR - Correctif B6 : validation stricte des prefixes de /v1/reputation.

La liste etait deja bornee (1 a 64 elements) mais chaque prefixe n'etait ni
borne en longueur ni valide en format. On contraint desormais chaque prefixe a
EXACTEMENT 8 caracteres hexadecimaux (4 octets), conformement au contrat. Cela
ferme un petit vecteur de DoS (chaine geante en cle de dict) et rejette les
requetes malformees avec une 422. La casse reste tolerante (le serveur
normalise deja en minuscules) -> aucune regression de correspondance.

Modifie : app/schemas.py

A executer DEPUIS /opt/blokqr/backend :
    cd /opt/blokqr/backend
    python3 patch_b6_reputation_prefixes.py

Idempotent. Contenus en base64. Abandon si une ancre est absente. Controle de syntaxe.
"""
import ast, base64, io, sys
dec=lambda s: base64.b64decode(s).decode("utf-8")
IMP=['ZnJvbSB0eXBpbmcgaW1wb3J0IExpc3QsIE9wdGlvbmFsCg==','ZnJvbSB0eXBpbmcgaW1wb3J0IEFubm90YXRlZCwgTGlzdCwgT3B0aW9uYWwK']
PREF=['ICAgIHByZWZpeGVzOiBMaXN0W3N0cl0gPSBGaWVsZCgKICAgICAgICAuLi4sIG1pbl9sZW5ndGg9MSwgbWF4X2xlbmd0aD02NCwKICAgICAgICBkZXNjcmlwdGlvbj0iUHLDqWZpeGVzIGRlIGhhc2ggKGhleCwgOCBjYXJhY3TDqHJlcyA9IDQgb2N0ZXRzKS4iLAogICAgKQo=','ICAgIHByZWZpeGVzOiBMaXN0W0Fubm90YXRlZFtzdHIsIEZpZWxkKHBhdHRlcm49ciJeWzAtOWEtZkEtRl17OH0kIildXSA9IEZpZWxkKAogICAgICAgIC4uLiwgbWluX2xlbmd0aD0xLCBtYXhfbGVuZ3RoPTY0LAogICAgICAgIGRlc2NyaXB0aW9uPSJQcsOpZml4ZXMgZGUgaGFzaCAoaGV4LCA4IGNhcmFjdMOocmVzID0gNCBvY3RldHMpLiIsCiAgICApCg==']

def apply_edits(path, edits):
    try:
        src=io.open(path,"r",encoding="utf-8").read()
    except FileNotFoundError:
        print("  [IGNORE] %s introuvable"%path); return True
    changed=False
    for old,new,marker in edits:
        if marker in src: continue
        if old not in src:
            print("  [ABORT] %s : ancre absente (%s) -> NON ecrit"%(path,marker)); return False
        src=src.replace(old,new,1); changed=True
    if not changed:
        print("  [OK   ] %s : deja applique (idempotent)"%path); return True
    try: ast.parse(src)
    except SyntaxError as e:
        print("  [ABORT] %s : syntaxe invalide -> NON ecrit (%s)"%(path,e)); return False
    io.open(path,"w",encoding="utf-8").write(src)
    print("  [PATCH] %s : modifie"%path); return True

def main():
    ok=apply_edits("app/schemas.py",[
        (dec(IMP[0]),dec(IMP[1]),"from typing import Annotated"),
        (dec(PREF[0]),dec(PREF[1]),'Field(pattern=r"^[0-9a-fA-F]{8}$")'),
    ])
    if not ok: sys.exit(1)
    print("\n>>> Termine. Reconstruisez l'image puis redemarrez le conteneur.")

if __name__=="__main__":
    main()
