#!/usr/bin/env python3
# -*- coding: ascii -*-
"""
Patch BlokQR : un domaine INEXISTANT/injoignable ne doit plus etre classe
MALVEILLANT. Un echec DNS leve desormais HostResolutionError (sous-classe de
SSRFError) -> signal 'domain_unresolved' (LOW) au lieu de 'ssrf_blocked_hop'
(CRITICAL), et verdict prudent (UNKNOWN). Les vraies tentatives SSRF (IP interne)
restent CRITICAL. Idempotent ; les editions obligatoires sont atomiques (aucune
ecriture si une ancre manque) ; l'edition pipeline (verdict UNKNOWN) est
optionnelle. Lancer depuis /opt/blokqr/backend.
"""
import ast, base64, os, sys

def d(s): return base64.b64decode(s).decode("utf-8")

EDITS = [
    ("app/security/ssrf_guard.py", "Y2xhc3MgU1NSRkVycm9yKEV4Y2VwdGlvbik6CiAgICAiIiJMZXbDqWUgbG9yc3F1J3VuZSBVUkwgZXN0IGp1Z8OpZSBub24gc8O7cmUgw6AgY29udGFjdGVyLiIiIgo=", "Y2xhc3MgU1NSRkVycm9yKEV4Y2VwdGlvbik6CiAgICAiIiJMZXbDqWUgbG9yc3F1J3VuZSBVUkwgZXN0IGp1Z8OpZSBub24gc8O7cmUgw6AgY29udGFjdGVyLiIiIgoKCmNsYXNzIEhvc3RSZXNvbHV0aW9uRXJyb3IoU1NSRkVycm9yKToKICAgICIiIkjDtHRlIGludHJvdXZhYmxlIC8gbm9uIHLDqXNvbHUgKMOpY2hlYyBETlMpIOKAlCBQQVMgdW5lIHRlbnRhdGl2ZSBTU1JGLgoKICAgIFNvdXMtY2xhc3NlIGRlIFNTUkZFcnJvciBwb3VyIHJlc3RlciByw6l0cm8tY29tcGF0aWJsZSBhdmVjIGxlcyBhcHBlbGFudHMgcXVpCiAgICBuZSBmaWx0cmVudCBxdWUgU1NSRkVycm9yLCB0b3V0IGVuIHBlcm1ldHRhbnQgdW4gdHJhaXRlbWVudCBkaXN0aW5jdCAodmVyZGljdAogICAgcHJ1ZGVudCBhdSBsaWV1IGRlIMKrIG1hbHZlaWxsYW50IMK7KSBsw6Agb8O5IG9uIHNhaXQgZmFpcmUgbGEgZGlmZsOpcmVuY2UuCiAgICAiIiIK", True),
    ("app/security/ssrf_guard.py", "ICAgICAgICBleGNlcHQgc29ja2V0LmdhaWVycm9yIGFzIGV4YzoKICAgICAgICAgICAgcmFpc2UgU1NSRkVycm9yKGYiUsOpc29sdXRpb24gRE5TIGltcG9zc2libGUgcG91ciB7aG9zdH06IHtleGN9IikgZnJvbSBleGM=", "ICAgICAgICBleGNlcHQgc29ja2V0LmdhaWVycm9yIGFzIGV4YzoKICAgICAgICAgICAgcmFpc2UgSG9zdFJlc29sdXRpb25FcnJvcigKICAgICAgICAgICAgICAgIGYiUsOpc29sdXRpb24gRE5TIGltcG9zc2libGUgcG91ciB7aG9zdH06IHtleGN9IikgZnJvbSBleGM=", True),
    ("app/security/ssrf_guard.py", "ICAgICAgICBpZiBub3QgcmVzb2x2ZWQ6CiAgICAgICAgICAgIHJhaXNlIFNTUkZFcnJvcihmIkF1Y3VuZSBhZHJlc3NlIHLDqXNvbHVlIHBvdXIge2hvc3R9Iik=", "ICAgICAgICBpZiBub3QgcmVzb2x2ZWQ6CiAgICAgICAgICAgIHJhaXNlIEhvc3RSZXNvbHV0aW9uRXJyb3IoZiJBdWN1bmUgYWRyZXNzZSByw6lzb2x1ZSBwb3VyIHtob3N0fSIp", True),
    ("app/analyzers/redirect_resolver.py", "ZnJvbSBhcHAuc2VjdXJpdHkuc3NyZl9ndWFyZCBpbXBvcnQgU1NSRkVycm9yLCByZXNvbHZlX2FuZF92YWxpZGF0ZQ==", "ZnJvbSBhcHAuc2VjdXJpdHkuc3NyZl9ndWFyZCBpbXBvcnQgSG9zdFJlc29sdXRpb25FcnJvciwgU1NSRkVycm9yLCByZXNvbHZlX2FuZF92YWxpZGF0ZQ==", True),
    ("app/analyzers/redirect_resolver.py", "ICAgICAgICAgICAgZXhjZXB0IFNTUkZFcnJvciBhcyBleGM6CiAgICAgICAgICAgICAgICBzaWduYWxzLmFwcGVuZChTaWduYWwoCiAgICAgICAgICAgICAgICAgICAgY29kZT0ic3NyZl9ibG9ja2VkX2hvcCIsCiAgICAgICAgICAgICAgICAgICAgdGl0bGU9IlJlZGlyZWN0aW9uIHZlcnMgdW5lIHJlc3NvdXJjZSBpbnRlcm5lIGJsb3F1w6llIiwKICAgICAgICAgICAgICAgICAgICBkZXRhaWw9c3RyKGV4YyksCiAgICAgICAgICAgICAgICAgICAgc2V2ZXJpdHk9U2V2ZXJpdHkuQ1JJVElDQUwsIHdlaWdodD00MCwgc291cmNlPV9TT1VSQ0UsCiAgICAgICAgICAgICAgICApKQogICAgICAgICAgICAgICAgYnJlYWs=", "ICAgICAgICAgICAgZXhjZXB0IEhvc3RSZXNvbHV0aW9uRXJyb3I6CiAgICAgICAgICAgICAgICAjIERvbWFpbmUgaW5leGlzdGFudCAvIGluam9pZ25hYmxlIDogY2Ugbidlc3QgUEFTIHVuZSBhdHRhcXVlCiAgICAgICAgICAgICAgICAjIFNTUkYuIFZlcmRpY3QgcHJ1ZGVudCAoamFtYWlzIMKrIG1hbHZlaWxsYW50IMK7IHN1ciB1biBsaWVuIG1vcnQpLgogICAgICAgICAgICAgICAgc2lnbmFscy5hcHBlbmQoU2lnbmFsKAogICAgICAgICAgICAgICAgICAgIGNvZGU9ImRvbWFpbl91bnJlc29sdmVkIiwKICAgICAgICAgICAgICAgICAgICB0aXRsZT0iRG9tYWluZSBpbnRyb3V2YWJsZSIsCiAgICAgICAgICAgICAgICAgICAgZGV0YWlsPSJDZXR0ZSBkZXN0aW5hdGlvbiBuZSByw6lzb3V0IHZlcnMgYXVjdW5lIGFkcmVzc2UgIgogICAgICAgICAgICAgICAgICAgICAgICAgICAiKGxpZW4gcHJvYmFibGVtZW50IG1vcnQgb3UgZXJyb27DqSkuIEFuYWx5c2UgIgogICAgICAgICAgICAgICAgICAgICAgICAgICAiaW1wb3NzaWJsZSA6IHZlcmRpY3QgcHJ1ZGVudCByZW5kdSBwYXIgc8OpY3VyaXTDqS4iLAogICAgICAgICAgICAgICAgICAgIHNldmVyaXR5PVNldmVyaXR5LkxPVywgd2VpZ2h0PTYsIHNvdXJjZT1fU09VUkNFLAogICAgICAgICAgICAgICAgKSkKICAgICAgICAgICAgICAgIGJyZWFrCiAgICAgICAgICAgIGV4Y2VwdCBTU1JGRXJyb3IgYXMgZXhjOgogICAgICAgICAgICAgICAgc2lnbmFscy5hcHBlbmQoU2lnbmFsKAogICAgICAgICAgICAgICAgICAgIGNvZGU9InNzcmZfYmxvY2tlZF9ob3AiLAogICAgICAgICAgICAgICAgICAgIHRpdGxlPSJSZWRpcmVjdGlvbiB2ZXJzIHVuZSByZXNzb3VyY2UgaW50ZXJuZSBibG9xdcOpZSIsCiAgICAgICAgICAgICAgICAgICAgZGV0YWlsPXN0cihleGMpLAogICAgICAgICAgICAgICAgICAgIHNldmVyaXR5PVNldmVyaXR5LkNSSVRJQ0FMLCB3ZWlnaHQ9NDAsIHNvdXJjZT1fU09VUkNFLAogICAgICAgICAgICAgICAgKSkKICAgICAgICAgICAgICAgIGJyZWFr", True),
    ("app/pipeline.py", "b3IgYW55KHMuY29kZSBpbiAoImR5bmFtaWNfZXJyb3IiLCAidW5yZXNvbHZlZF9yZWRpcmVjdCIpIGZvciBzIGluIHNpZ25hbHMp", "b3IgYW55KHMuY29kZSBpbiAoImR5bmFtaWNfZXJyb3IiLCAidW5yZXNvbHZlZF9yZWRpcmVjdCIsICJkb21haW5fdW5yZXNvbHZlZCIpIGZvciBzIGluIHNpZ25hbHMp", False),
]

def main():
    cache = {}
    def load(f):
        if f not in cache:
            if not os.path.exists(f):
                cache[f] = None
            else:
                cache[f] = open(f, encoding="utf-8").read()
        return cache[f]

    plans = []  # (file, kind)
    # Phase 1 : planification (aucune ecriture). Abort si une OBLIGATOIRE manque.
    for i, (f, ob, nb, mand) in enumerate(EDITS, 1):
        src = load(f)
        old, new = d(ob), d(nb)
        if src is None:
            if mand:
                print("[ABORT] edition %d : %s introuvable" % (i, f)); sys.exit(1)
            print("[WARN] edition %d (option) : %s introuvable -> ignoree" % (i, f)); continue
        if new in src:
            plans.append((i, f, "skip")); continue
        if old not in src:
            if mand:
                print("[ABORT] edition %d : ancre introuvable dans %s -> AUCUNE ecriture" % (i, f))
                sys.exit(1)
            plans.append((i, f, "missing-opt")); continue
        cache[f] = src.replace(old, new, 1)  # stage en memoire
        plans.append((i, f, "apply"))

    # Phase 2 : validation AST des fichiers modifies, puis ecriture.
    changed = sorted({f for (i, f, k) in plans if k == "apply"})
    for f in changed:
        ast.parse(cache[f])
    for f in changed:
        with open(f, "w", encoding="utf-8") as fh:
            fh.write(cache[f])

    for i, f, k in plans:
        label = {"apply":"[OK]  ", "skip":"[SKIP]", "missing-opt":"[WARN]"}[k]
        suffix = " (option : ancre absente, ignoree)" if k == "missing-opt" else ""
        print("%s edition %d (%s)%s" % (label, i, f, suffix))
    if changed:
        print("\n[OK] Correctif applique. Reconstruisez le conteneur.")
    else:
        print("\nRien a faire (deja applique).")

if __name__ == "__main__":
    main()
