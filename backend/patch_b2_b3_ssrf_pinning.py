# -*- coding: utf-8 -*-
"""
BlokQR - Correctifs B2 (pinning IP anti DNS rebinding) + B3 (CGNAT).

B2 : la connexion sortante (suivi de redirections) etait ouverte apres une
2e resolution DNS par httpx -> fenetre de DNS rebinding (TOCTOU). On epingle
desormais la connexion TCP sur l'IP deja validee par le guard SSRF, sans rien
changer a la couche TLS (SNI + verification du certificat restent faits contre
le nom d'hote, exactement comme une requete httpx normale).

B3 : la plage CGNAT 100.64.0.0/10 (RFC 6598) est desormais bloquee par le guard.

Cree   : app/security/pinned_client.py
Modifie: app/security/ssrf_guard.py        (CGNAT)
         app/analyzers/redirect_resolver.py (import + transport epingle + pin)

A executer DEPUIS /opt/blokqr/backend :
    cd /opt/blokqr/backend
    python3 patch_b2_b3_ssrf_pinning.py

Idempotent. Contenus embarques en base64 (ASCII pur -> aucun souci d'encodage).
Abandon sans ecriture si une ancre attendue est absente. Controle de syntaxe.
"""
import ast
import base64
import io
import os
import sys

dec = lambda s: base64.b64decode(s).decode("utf-8")

PINNED = "IiIiClRyYW5zcG9ydCBodHRweCDDqXBpbmdsw6kgc3VyIGwnSVAgdmFsaWTDqWUgKGFudGkgRE5TIHJlYmluZGluZykuCgpQcm9ibMOobWUgOiBgcmVzb2x2ZV9hbmRfdmFsaWRhdGVgIChzc3JmX2d1YXJkKSByw6lzb3V0IGwnaMO0dGUsIHbDqXJpZmllIHF1ZQpsJ0lQIGVzdCBwdWJsaXF1ZSwgcHVpcyBvbiBmYWlzYWl0IGBjbGllbnQuZ2V0KHVybClgIOKAlCBldCBodHRweCAqKnJlLXLDqXNvbHZhaXQqKgpsJ2jDtHRlIGRlIHNvbiBjw7R0w6kuIEVudHJlIGxlcyBkZXV4IHLDqXNvbHV0aW9ucywgdW4gRE5TIGNvbnRyw7Rsw6kgcGFyCmwnYXR0YXF1YW50IHBldXQgcmVudm95ZXIgdW5lIElQIHB1YmxpcXVlICh2YWxpZGF0aW9uKSBwdWlzIHVuZSBJUCBpbnRlcm5lCihjb25uZXhpb24pIDogYydlc3QgbGUgRE5TIHJlYmluZGluZyAoVE9DVE9VKS4KClNvbHV0aW9uIDogb24gbmUgY2hhbmdlIFJJRU4gw6AgbGEgY291Y2hlIFRMUyAoVVJMLCBTTkksIHbDqXJpZmljYXRpb24gZGUKY2VydGlmaWNhdCByZXN0ZW50IGfDqXLDqXMgbm9ybWFsZW1lbnQgcGFyIGh0dHB4IGNvbnRyZSBsZSBub20gZCdow7R0ZSkuIE9uCnJlbXBsYWNlIHVuaXF1ZW1lbnQgbCdhZHJlc3NlIGRlIGNvbm5leGlvbiBUQ1AgcGFyIGwnSVAgZMOpasOgIHZhbGlkw6llLCB2aWEgbGUKYmFja2VuZCByw6lzZWF1IGRlIGh0dHBjb3JlLiBMYSBzb2NrZXQgZXN0IGRvbmMgb3V2ZXJ0ZSB2ZXJzIGwnSVAgw6lwaW5nbMOpZQooYXVjdW5lIHNlY29uZGUgcsOpc29sdXRpb24pLCB0YW5kaXMgcXVlIGxlIGNlcnRpZmljYXQgZXN0IHRvdWpvdXJzIHbDqXJpZmnDqQpjb250cmUgbGUgbm9tIGQnaMO0dGUgZCdvcmlnaW5lIOKAlCBleGFjdGVtZW50IGNvbW1lIHVuZSByZXF1w6p0ZSBodHRweCBub3JtYWxlLgoKVXNhZ2UgOgogICAgcGluOiBkaWN0W3N0ciwgc3RyXSA9IHt9CiAgICB0cmFuc3BvcnQgPSBtYWtlX3Bpbm5lZF90cmFuc3BvcnQocGluKQogICAgYXN5bmMgd2l0aCBodHRweC5Bc3luY0NsaWVudCh0cmFuc3BvcnQ9dHJhbnNwb3J0LCBmb2xsb3dfcmVkaXJlY3RzPUZhbHNlLAogICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICBsaW1pdHM9aHR0cHguTGltaXRzKG1heF9rZWVwYWxpdmVfY29ubmVjdGlvbnM9MCksCiAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIC4uLikgYXMgY2xpZW50OgogICAgICAgICMgYXZhbnQgY2hhcXVlIHJlcXXDqnRlLCBhcHLDqHMgdmFsaWRhdGlvbiBTU1JGIDoKICAgICAgICBwaW5bcmVzb2x2ZWQuaG9zdF0gPSByZXNvbHZlZC5wcmltYXJ5X2lwCiAgICAgICAgcmVzcCA9IGF3YWl0IGNsaWVudC5nZXQodXJsKQoKTGUgZGljdCBgcGluYCBlc3QgcGFydGFnw6kgKHBhciByw6lmw6lyZW5jZSkgYXZlYyBsZSBiYWNrZW5kIDogaWwgc3VmZml0IGRlIGxlCm1ldHRyZSDDoCBqb3VyIGF2YW50IGNoYXF1ZSBzYXV0LiBgbWF4X2tlZXBhbGl2ZV9jb25uZWN0aW9ucz0wYCBnYXJhbnRpdCBxdSd1bmUKbm91dmVsbGUgY29ubmV4aW9uIChkb25jIHVuIG5vdXZlYXUgY29ubmVjdF90Y3Agw6lwaW5nbMOpKSBlc3Qgb3V2ZXJ0ZSDDoCBjaGFxdWUKcmVxdcOqdGUsIHkgY29tcHJpcyBhcHLDqHMgdW5lIHJlZGlyZWN0aW9uIHZlcnMgdW4gYXV0cmUgaMO0dGUuCiIiIgpmcm9tIF9fZnV0dXJlX18gaW1wb3J0IGFubm90YXRpb25zCgpmcm9tIHR5cGluZyBpbXBvcnQgRGljdCwgT3B0aW9uYWwKCmltcG9ydCBodHRwY29yZQppbXBvcnQgaHR0cHgKCgpjbGFzcyBfUGlubmVkQmFja2VuZChodHRwY29yZS5BbnlJT0JhY2tlbmQpOgogICAgIiIiQmFja2VuZCByw6lzZWF1IHF1aSBmb3JjZSBsJ2FkcmVzc2UgZGUgY29ubmV4aW9uIFRDUCDDoCBsJ0lQIHZhbGlkw6llLiIiIgoKICAgIGRlZiBfX2luaXRfXyhzZWxmLCBwaW46IERpY3Rbc3RyLCBzdHJdKSAtPiBOb25lOgogICAgICAgIHN1cGVyKCkuX19pbml0X18oKQogICAgICAgIHNlbGYuX3BpbiA9IHBpbgoKICAgIGFzeW5jIGRlZiBjb25uZWN0X3RjcChzZWxmLCBob3N0LCBwb3J0LCB0aW1lb3V0PU5vbmUsIGxvY2FsX2FkZHJlc3M9Tm9uZSwKICAgICAgICAgICAgICAgICAgICAgICAgICBzb2NrZXRfb3B0aW9ucz1Ob25lKToKICAgICAgICAjIGBob3N0YCBlc3QgbGUgbm9tIGQnaMO0dGUgZCdvcmlnaW5lIChodHRwY29yZSBsZSBkw6ljb2RlIGVuIHN0cikuIE9uIGxlCiAgICAgICAgIyByZW1wbGFjZSBwYXIgbCdJUCBwcsOpLXZhbGlkw6llIHNpIGVsbGUgZXN0IGNvbm51ZSA7IHNpbm9uIGNvbXBvcnRlbWVudAogICAgICAgICMgbm9ybWFsICh1dGlsZSBwb3VyIHVuIGjDtHRlIG5vbiBlbmNvcmUgw6lwaW5nbMOpKS4KICAgICAgICB0YXJnZXQgPSBzZWxmLl9waW4uZ2V0KGhvc3QsIGhvc3QpCiAgICAgICAgcmV0dXJuIGF3YWl0IHN1cGVyKCkuY29ubmVjdF90Y3AoCiAgICAgICAgICAgIHRhcmdldCwgcG9ydCwgdGltZW91dD10aW1lb3V0LAogICAgICAgICAgICBsb2NhbF9hZGRyZXNzPWxvY2FsX2FkZHJlc3MsIHNvY2tldF9vcHRpb25zPXNvY2tldF9vcHRpb25zLAogICAgICAgICkKCgpkZWYgbWFrZV9waW5uZWRfdHJhbnNwb3J0KHBpbjogRGljdFtzdHIsIHN0cl0sCiAgICAgICAgICAgICAgICAgICAgICAgICAgcmV0cmllczogaW50ID0gMCkgLT4gaHR0cHguQXN5bmNIVFRQVHJhbnNwb3J0OgogICAgIiIiQ29uc3RydWl0IHVuIHRyYW5zcG9ydCBodHRweCBkb250IGxlcyBjb25uZXhpb25zIHNvbnQgw6lwaW5nbMOpZXMgdmlhIGBwaW5gLgoKICAgIGBwaW5gIGVzdCB1biBkaWN0IHtob3N0bmFtZTogaXB9IHBhcnRhZ8OpIHBhciByw6lmw6lyZW5jZSA6IGxlIG1ldHRyZSDDoCBqb3VyCiAgICBhdmFudCBjaGFxdWUgcmVxdcOqdGUuIEZhaWwtY2xvc2VkIDogc2kgbCdhdHRyaWJ1dCBpbnRlcm5lIGF0dGVuZHUgZGUKICAgIGh0dHBjb3JlIGRpc3BhcmHDrnQgKGNoYW5nZW1lbnQgZGUgdmVyc2lvbiksIG9uIGzDqHZlIHVuZSBleGNlcHRpb24gcGx1dMO0dAogICAgcXVlIGRlIHBlcmRyZSBzaWxlbmNpZXVzZW1lbnQgbGUgcGlubmluZyAoPSBwcm90ZWN0aW9uIFNTUkYpLgogICAgIiIiCiAgICB0cmFuc3BvcnQgPSBodHRweC5Bc3luY0hUVFBUcmFuc3BvcnQocmV0cmllcz1yZXRyaWVzKQogICAgcG9vbCA9IHRyYW5zcG9ydC5fcG9vbAogICAgaWYgbm90IGhhc2F0dHIocG9vbCwgIl9uZXR3b3JrX2JhY2tlbmQiKToKICAgICAgICByYWlzZSBSdW50aW1lRXJyb3IoCiAgICAgICAgICAgICJodHRwY29yZTogYXR0cmlidXQgJ19uZXR3b3JrX2JhY2tlbmQnIGludHJvdXZhYmxlIOKAlCBwaW5uaW5nIFNTUkYgIgogICAgICAgICAgICAiaW1wb3NzaWJsZS4gUmVmdXMgZGUgY29udGludWVyIHNhbnMgcHJvdGVjdGlvbiAoZmFpbC1jbG9zZWQpLiIKICAgICAgICApCiAgICBwb29sLl9uZXR3b3JrX2JhY2tlbmQgPSBfUGlubmVkQmFja2VuZChwaW4pCiAgICByZXR1cm4gdHJhbnNwb3J0Cg=="

SSRF = ['ICAgICAgICBvciAoaXNpbnN0YW5jZShpcCwgaXBhZGRyZXNzLklQdjRBZGRyZXNzKSBhbmQgaXAgaW4gaXBhZGRyZXNzLmlwX25ldHdvcmsoIjE2OS4yNTQuMC4wLzE2IikpCg==', 'ICAgICAgICBvciAoaXNpbnN0YW5jZShpcCwgaXBhZGRyZXNzLklQdjRBZGRyZXNzKSBhbmQgaXAgaW4gaXBhZGRyZXNzLmlwX25ldHdvcmsoIjE2OS4yNTQuMC4wLzE2IikpCiAgICAgICAgb3IgKGlzaW5zdGFuY2UoaXAsIGlwYWRkcmVzcy5JUHY0QWRkcmVzcykgYW5kIGlwIGluIGlwYWRkcmVzcy5pcF9uZXR3b3JrKCIxMDAuNjQuMC4wLzEwIikpCg==']
IMP  = ['ZnJvbSBhcHAuc2VjdXJpdHkuc3NyZl9ndWFyZCBpbXBvcnQgU1NSRkVycm9yLCByZXNvbHZlX2FuZF92YWxpZGF0ZQo=', 'ZnJvbSBhcHAuc2VjdXJpdHkuc3NyZl9ndWFyZCBpbXBvcnQgU1NSRkVycm9yLCByZXNvbHZlX2FuZF92YWxpZGF0ZQpmcm9tIGFwcC5zZWN1cml0eS5waW5uZWRfY2xpZW50IGltcG9ydCBtYWtlX3Bpbm5lZF90cmFuc3BvcnQK']
TR   = ['ICAgIHRyYW5zcG9ydCA9IGh0dHB4LkFzeW5jSFRUUFRyYW5zcG9ydChyZXRyaWVzPTApCiAgICBoZWFkZXJzID0geyJVc2VyLUFnZW50IjogX01PQklMRV9VQSwgIkFjY2VwdCI6ICJ0ZXh0L2h0bWwsKi8qIn0KCiAgICBhc3luYyB3aXRoIGh0dHB4LkFzeW5jQ2xpZW50KAogICAgICAgIGZvbGxvd19yZWRpcmVjdHM9RmFsc2UsCiAgICAgICAgdGltZW91dD1zZXR0aW5ncy5odHRwX3RpbWVvdXRfc2Vjb25kcywKICAgICAgICB0cmFuc3BvcnQ9dHJhbnNwb3J0LAogICAgICAgIGhlYWRlcnM9aGVhZGVycywKICAgICAgICBtYXhfcmVkaXJlY3RzPTAsCiAgICApIGFzIGNsaWVudDo=', 'ICAgICMgQ29ubmV4aW9ucyDDqXBpbmdsw6llcyBzdXIgbCdJUCB2YWxpZMOpZSAoYW50aSBETlMgcmViaW5kaW5nKSA6IGxlIGRpY3QgZXN0CiAgICAjIG1pcyDDoCBqb3VyIGF2YW50IGNoYXF1ZSBzYXV0LCBhcHLDqHMgbGEgdmFsaWRhdGlvbiBTU1JGLgogICAgcGluOiBkaWN0W3N0ciwgc3RyXSA9IHt9CiAgICB0cmFuc3BvcnQgPSBtYWtlX3Bpbm5lZF90cmFuc3BvcnQocGluKQogICAgaGVhZGVycyA9IHsiVXNlci1BZ2VudCI6IF9NT0JJTEVfVUEsICJBY2NlcHQiOiAidGV4dC9odG1sLCovKiJ9CgogICAgYXN5bmMgd2l0aCBodHRweC5Bc3luY0NsaWVudCgKICAgICAgICBmb2xsb3dfcmVkaXJlY3RzPUZhbHNlLAogICAgICAgIHRpbWVvdXQ9c2V0dGluZ3MuaHR0cF90aW1lb3V0X3NlY29uZHMsCiAgICAgICAgdHJhbnNwb3J0PXRyYW5zcG9ydCwKICAgICAgICBoZWFkZXJzPWhlYWRlcnMsCiAgICAgICAgbWF4X3JlZGlyZWN0cz0wLAogICAgICAgICMgUGFzIGRlIHLDqXV0aWxpc2F0aW9uIGRlIGNvbm5leGlvbiA6IGNoYXF1ZSByZXF1w6p0ZSByw6ktb3V2cmUgdW5lIHNvY2tldAogICAgICAgICMgKGRvbmMgdW4gY29ubmVjdF90Y3Agw6lwaW5nbMOpKSwgeSBjb21wcmlzIGFwcsOocyByZWRpcmVjdGlvbi4KICAgICAgICBsaW1pdHM9aHR0cHguTGltaXRzKG1heF9rZWVwYWxpdmVfY29ubmVjdGlvbnM9MCksCiAgICApIGFzIGNsaWVudDo=']
PIN  = ['ICAgICAgICAgICAgICAgIGJyZWFrCgogICAgICAgICAgICB0cnk6CiAgICAgICAgICAgICAgICByZXNwID0gYXdhaXQgY2xpZW50LmdldChjdXJyZW50KQo=', 'ICAgICAgICAgICAgICAgIGJyZWFrCgogICAgICAgICAgICAjIMOJcGluZ2xhZ2UgOiBsYSBjb25uZXhpb24gaXJhIHN1ciBsJ0lQIHZhbGlkw6llLCBwYXMgc3VyIHVuZQogICAgICAgICAgICAjIG5vdXZlbGxlIHLDqXNvbHV0aW9uIEROUyAoZmVybWV0dXJlIGRlIGxhIGZlbsOqdHJlIGRlIHJlYmluZGluZykuCiAgICAgICAgICAgIHBpbltyZXNvbHZlZC5ob3N0XSA9IHJlc29sdmVkLnByaW1hcnlfaXAKCiAgICAgICAgICAgIHRyeToKICAgICAgICAgICAgICAgIHJlc3AgPSBhd2FpdCBjbGllbnQuZ2V0KGN1cnJlbnQpCg==']


def write_new_file(path, content):
    if os.path.exists(path):
        cur = io.open(path, "r", encoding="utf-8").read()
        if cur == content:
            print("  [OK   ] %s : deja present (identique)" % path); return True
    try:
        ast.parse(content)
    except SyntaxError as e:
        print("  [ABORT] %s : syntaxe invalide -> NON ecrit (%s)" % (path, e)); return False
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("  [PATCH] %s : cree/mis a jour" % path); return True


def apply_edits(path, edits):
    # edits : liste de (old, new, marker)
    try:
        src = io.open(path, "r", encoding="utf-8").read()
    except FileNotFoundError:
        print("  [IGNORE] %s introuvable" % path); return True
    changed = False
    for old, new, marker in edits:
        if marker in src:
            continue  # deja applique
        if old not in src:
            print("  [ABORT] %s : ancre absente (%s) -> NON ecrit" % (path, marker)); return False
        src = src.replace(old, new, 1); changed = True
    if not changed:
        print("  [OK   ] %s : deja applique (idempotent)" % path); return True
    try:
        ast.parse(src)
    except SyntaxError as e:
        print("  [ABORT] %s : syntaxe invalide -> NON ecrit (%s)" % (path, e)); return False
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(src)
    print("  [PATCH] %s : modifie" % path); return True


def main():
    ok = True
    ok &= write_new_file("app/security/pinned_client.py", dec(PINNED))
    ok &= apply_edits("app/security/ssrf_guard.py", [
        (dec(SSRF[0]), dec(SSRF[1]), "100.64.0.0/10"),
    ])
    ok &= apply_edits("app/analyzers/redirect_resolver.py", [
        (dec(IMP[0]), dec(IMP[1]), "from app.security.pinned_client import make_pinned_transport"),
        (dec(TR[0]),  dec(TR[1]),  "make_pinned_transport(pin)"),
        (dec(PIN[0]), dec(PIN[1]), "pin[resolved.host] = resolved.primary_ip"),
    ])
    if not ok:
        print("\n>>> Des erreurs sont survenues (voir [ABORT]). Aucun fichier partiel ecrit.")
        sys.exit(1)
    print("\n>>> Termine. Reconstruisez l'image puis redemarrez le conteneur.")


if __name__ == "__main__":
    main()
