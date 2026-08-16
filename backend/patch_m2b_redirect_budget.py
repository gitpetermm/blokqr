# -*- coding: utf-8 -*-
"""
BlokQR - Module 2b (n4) : budget mur d'horloge sur la resolution des redirections.

Corrige le "failed" RESIDUEL du chemin rapide : une cible lente / multi-redirections
ne peut plus consommer tout le budget global (22 s) et provoquer un verdict UNKNOWN.
Au-dela de redirect_budget_seconds (12 s par defaut), resolve_chain s'arrete et
emet le signal unresolved_redirect (deja dans l'ensemble fail-closed). Borne et
deterministe.

Modifie :
  * app/config.py                       (reglage redirect_budget_seconds)
  * app/analyzers/redirect_resolver.py  (import monotonic + deadline + controle)

A executer DEPUIS /opt/blokqr/backend :
    cd /opt/blokqr/backend
    python3 patch_m2b_redirect_budget.py

Idempotent. Contenus base64. Abandon si une ancre est absente. Controle syntaxe.
"""
import ast, base64, io, os, sys
dec=lambda s: base64.b64decode(s).decode("utf-8")

CFG=[('ICAgICMgLS0tIFLDqXNvbHV0aW9uIGRlcyByZWRpcmVjdGlvbnMgLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0KICAgIG1heF9yZWRpcmVjdF9ob3BzOiBpbnQgPSAxNQogICAgaHR0cF90aW1lb3V0X3NlY29uZHM6IGZsb2F0ID0gOC4w', 'ICAgICMgLS0tIFLDqXNvbHV0aW9uIGRlcyByZWRpcmVjdGlvbnMgLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0KICAgIG1heF9yZWRpcmVjdF9ob3BzOiBpbnQgPSAxNQogICAgaHR0cF90aW1lb3V0X3NlY29uZHM6IGZsb2F0ID0gOC4wCiAgICAjIEJ1ZGdldCBtdXIgZCdob3Jsb2dlIFRPVEFMIGRlIGxhIHLDqXNvbHV0aW9uIGRlcyByZWRpcmVjdGlvbnMgKHRvdXMgc2F1dHMKICAgICMgY29uZm9uZHVzKS4gQXUtZGVsw6AsIG9uIHMnYXJyw6p0ZSAoc2lnbmFsIHVucmVzb2x2ZWRfcmVkaXJlY3QgLT4gdmVyZGljdAogICAgIyBwcnVkZW50KSBhdSBsaWV1IGRlIGxhaXNzZXIgdW5lIGNpYmxlIGxlbnRlIGNvbnNvbW1lciB0b3V0IGxlIGJ1ZGdldCBnbG9iYWwKICAgICMgZXQgcHJvdm9xdWVyIHVuIHZlcmRpY3QgVU5LTk9XTi4gw4AgZ2FyZGVyIHNvdXMgb3ZlcmFsbF9idWRnZXRfc2Vjb25kcy4KICAgIHJlZGlyZWN0X2J1ZGdldF9zZWNvbmRzOiBmbG9hdCA9IDEyLjA=', 'redirect_budget_seconds: float = 12.0')]
RR=[('aW1wb3J0IHJlCmZyb20gdHlwaW5nIGltcG9ydCBMaXN0LCBPcHRpb25hbCwgVHVwbGU=', 'aW1wb3J0IHJlCmZyb20gdGltZSBpbXBvcnQgbW9ub3RvbmljCmZyb20gdHlwaW5nIGltcG9ydCBMaXN0LCBPcHRpb25hbCwgVHVwbGU=', 'from time import monotonic'), ('ICAgIHBpbjogZGljdFtzdHIsIHN0cl0gPSB7fQogICAgdHJhbnNwb3J0ID0gbWFrZV9waW5uZWRfdHJhbnNwb3J0KHBpbikKICAgIGhlYWRlcnMgPSB7IlVzZXItQWdlbnQiOiBfTU9CSUxFX1VBLCAiQWNjZXB0IjogInRleHQvaHRtbCwqLyoifQ==', 'ICAgIHBpbjogZGljdFtzdHIsIHN0cl0gPSB7fQogICAgdHJhbnNwb3J0ID0gbWFrZV9waW5uZWRfdHJhbnNwb3J0KHBpbikKICAgIGhlYWRlcnMgPSB7IlVzZXItQWdlbnQiOiBfTU9CSUxFX1VBLCAiQWNjZXB0IjogInRleHQvaHRtbCwqLyoifQoKICAgICMgQnVkZ2V0IG11ciBkJ2hvcmxvZ2UgOiBib3JuZSBsYSByw6lzb2x1dGlvbiBtw6ptZSBmYWNlIMOgIHVuZSBjaWJsZSBsZW50ZSBvdQogICAgIyB1bmUgbG9uZ3VlIGNoYcOubmUsIHBvdXIgZ2FyZGVyIGxlIGNoZW1pbiByYXBpZGUgc291cyBsZSBidWRnZXQgZ2xvYmFsLgogICAgZGVhZGxpbmUgPSBtb25vdG9uaWMoKSArIHNldHRpbmdzLnJlZGlyZWN0X2J1ZGdldF9zZWNvbmRz', 'deadline = monotonic() + settings.redirect_budget_seconds'), ('ICAgICAgICBmb3IgaW5kZXggaW4gcmFuZ2Uoc2V0dGluZ3MubWF4X3JlZGlyZWN0X2hvcHMgKyAxKToKICAgICAgICAgICAgaWYgY3VycmVudCBpbiBzZWVuOg==', 'ICAgICAgICBmb3IgaW5kZXggaW4gcmFuZ2Uoc2V0dGluZ3MubWF4X3JlZGlyZWN0X2hvcHMgKyAxKToKICAgICAgICAgICAgaWYgbW9ub3RvbmljKCkgPiBkZWFkbGluZToKICAgICAgICAgICAgICAgIHNpZ25hbHMuYXBwZW5kKFNpZ25hbCgKICAgICAgICAgICAgICAgICAgICBjb2RlPSJ1bnJlc29sdmVkX3JlZGlyZWN0IiwKICAgICAgICAgICAgICAgICAgICB0aXRsZT0iUsOpc29sdXRpb24gZGVzIHJlZGlyZWN0aW9ucyBpbnRlcnJvbXB1ZSAoZMOpbGFpIGTDqXBhc3PDqSkiLAogICAgICAgICAgICAgICAgICAgIGRldGFpbD0iTGEgY2hhw65uZSBkZSByZWRpcmVjdGlvbnMgbidhIHBhcyBwdSDDqnRyZSBlbnRpw6hyZW1lbnQgIgogICAgICAgICAgICAgICAgICAgICAgICAgICAicsOpc29sdWUgZGFucyBsZSBidWRnZXQgaW1wYXJ0aSA7IHZlcmRpY3QgcHJ1ZGVudCByZW5kdSAiCiAgICAgICAgICAgICAgICAgICAgICAgICAgICJwYXIgc8OpY3VyaXTDqS4gVm91cyBwb3V2ZXogcsOpZXNzYXllci4iLAogICAgICAgICAgICAgICAgICAgIHNldmVyaXR5PVNldmVyaXR5LkxPVywgd2VpZ2h0PTAsIHNvdXJjZT1fU09VUkNFLAogICAgICAgICAgICAgICAgKSkKICAgICAgICAgICAgICAgIGJyZWFrCiAgICAgICAgICAgIGlmIGN1cnJlbnQgaW4gc2Vlbjo=', 'if monotonic() > deadline:')]

def apply_edits(path, edits):
    if not os.path.exists(path):
        print("  [ABORT] %s introuvable"%path); return False
    src=io.open(path,"r",encoding="utf-8").read(); changed=False
    for old_b,new_b,marker in edits:
        if marker in src: continue
        old=dec(old_b)
        if old not in src:
            print("  [ABORT] %s : ancre absente (%s) -> NON ecrit"%(path,marker)); return False
        src=src.replace(old,dec(new_b),1); changed=True
    if not changed:
        print("  [OK   ] %s : deja applique (idempotent)"%path); return True
    try: ast.parse(src)
    except SyntaxError as e:
        print("  [ABORT] %s : syntaxe invalide -> NON ecrit (%s)"%(path,e)); return False
    io.open(path,"w",encoding="utf-8").write(src)
    print("  [PATCH] %s : modifie"%path); return True

def main():
    ok=True
    ok&=apply_edits("app/config.py", CFG)
    ok&=apply_edits("app/analyzers/redirect_resolver.py", RR)
    if not ok: sys.exit(1)
    print("\n>>> Termine. Reconstruisez l'image puis redemarrez le conteneur.")

if __name__=="__main__":
    main()
