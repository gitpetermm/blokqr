#!/usr/bin/env python3
# -*- coding: ascii -*-
"""
Patch BlokQR : reactive le flux URLhaus (abuse.ch) qui renvoyait 401. URLhaus
exige desormais un en-tete Auth-Key. Ce patch ajoute le reglage 'urlhaus_auth_key'
et l'envoie en en-tete ; sans cle, la source est marquee indisponible (plus de
401). La cle se met dans le fichier .env (URLHAUS_AUTH_KEY=...), JAMAIS dans le
code. Idempotent, abort si ancre absente, AST validee. Lancer depuis
/opt/blokqr/backend.
"""
import ast, base64, os, sys
def d(s): return base64.b64decode(s).decode("utf-8")
EDITS=[
    ("app/config.py", "ICAgIHVybHNjYW5fYXBpX2tleTogc3RyID0gRmllbGQoZGVmYXVsdD0iIik=", "ICAgIHVybHNjYW5fYXBpX2tleTogc3RyID0gRmllbGQoZGVmYXVsdD0iIikKICAgIHVybGhhdXNfYXV0aF9rZXk6IHN0ciA9IEZpZWxkKGRlZmF1bHQ9IiIpICAjIGFidXNlLmNoIEF1dGgtS2V5IChVUkxoYXVzIGwnZXhpZ2Up"),
    ("app/analyzers/threat_intel.py", "ICAgIG5hbWUgPSAidXJsaGF1cyIKICAgIGlmIG5vdCBzZXR0aW5ncy5lbmFibGVfdXJsaGF1czoKICAgICAgICByZXR1cm4gVGhyZWF0SW50ZWxSZXN1bHQocHJvdmlkZXI9bmFtZSwgYXZhaWxhYmxlPUZhbHNlKQogICAgdHJ5OgogICAgICAgIHJlc3AgPSBhd2FpdCBjbGllbnQucG9zdCgKICAgICAgICAgICAgImh0dHBzOi8vdXJsaGF1cy1hcGkuYWJ1c2UuY2gvdjEvdXJsLyIsCiAgICAgICAgICAgIGRhdGE9eyJ1cmwiOiB1cmx9LAogICAgICAgICk=", "ICAgIG5hbWUgPSAidXJsaGF1cyIKICAgIGlmIG5vdCBzZXR0aW5ncy5lbmFibGVfdXJsaGF1czoKICAgICAgICByZXR1cm4gVGhyZWF0SW50ZWxSZXN1bHQocHJvdmlkZXI9bmFtZSwgYXZhaWxhYmxlPUZhbHNlKQogICAgaWYgbm90IHNldHRpbmdzLnVybGhhdXNfYXV0aF9rZXk6CiAgICAgICAgIyBVUkxoYXVzIGV4aWdlIHVuZSBBdXRoLUtleSAoYWJ1c2UuY2gpIDogc2FucyBjbMOpLCBvbiBuJ2FwcGVsbGUgcGFzCiAgICAgICAgIyAow6l2aXRlIHVuIDQwMSBzeXN0w6ltYXRpcXVlKSBldCBvbiBtYXJxdWUgbGEgc291cmNlIGluZGlzcG9uaWJsZS4KICAgICAgICByZXR1cm4gVGhyZWF0SW50ZWxSZXN1bHQoCiAgICAgICAgICAgIHByb3ZpZGVyPW5hbWUsIGF2YWlsYWJsZT1GYWxzZSwKICAgICAgICAgICAgZGV0YWlsPSJDbMOpIGFidXNlLmNoIChBdXRoLUtleSkgbm9uIGNvbmZpZ3Vyw6llLiIsCiAgICAgICAgKQogICAgdHJ5OgogICAgICAgIHJlc3AgPSBhd2FpdCBjbGllbnQucG9zdCgKICAgICAgICAgICAgImh0dHBzOi8vdXJsaGF1cy1hcGkuYWJ1c2UuY2gvdjEvdXJsLyIsCiAgICAgICAgICAgIGRhdGE9eyJ1cmwiOiB1cmx9LAogICAgICAgICAgICBoZWFkZXJzPXsiQXV0aC1LZXkiOiBzZXR0aW5ncy51cmxoYXVzX2F1dGhfa2V5fSwKICAgICAgICAp"),
]
def main():
    cache={}
    def load(f):
        if f not in cache:
            cache[f]=open(f,encoding="utf-8").read() if os.path.exists(f) else None
        return cache[f]
    plans=[]
    for i,(f,ob,nb) in enumerate(EDITS,1):
        src=load(f); old,new=d(ob),d(nb)
        if src is None: print("[ABORT] %s introuvable"%f); sys.exit(1)
        if new in src: plans.append((i,f,"skip")); continue
        if old not in src: print("[ABORT] edition %d : ancre introuvable dans %s"%(i,f)); sys.exit(1)
        cache[f]=src.replace(old,new,1); plans.append((i,f,"apply"))
    changed=sorted({f for i,f,k in plans if k=="apply"})
    for f in changed: ast.parse(cache[f])
    for f in changed: open(f,"w",encoding="utf-8").write(cache[f])
    for i,f,k in plans:
        print(("[OK]  " if k=="apply" else "[SKIP]")+" edition %d (%s)"%(i,f))
    print("\n%s"%("[OK] Patch applique. Ajoutez URLHAUS_AUTH_KEY au .env puis rebuild." if changed else "Rien a faire (deja applique)."))
if __name__=="__main__": main()
