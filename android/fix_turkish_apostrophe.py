#!/usr/bin/env python3
"""
Patch ciblé : corrige l'apostrophe ASCII non échappée dans le libellé turc
`result_advanced_locked_body` de res/values-tr/strings.xml, qui faisait planter
AAPT2 lors du :app:mergeDebugResources.

Avant (mauvais) :
  <string name="result_advanced_locked_body">... BlokQR Pro'ya dahildir.</string>
Apres (bon) :
  <string name="result_advanced_locked_body">... BlokQR Pro\'ya dahildir.</string>

En complement, ce script PARCOURT toutes les locales et signale toute
apostrophe ASCII non echappee dans nos 5 cles du Paquet 1, par precaution.
Le script est idempotent : il ne re-echappe pas si c'est deja fait.

Usage (depuis la racine du projet Android) :
    python3 fix_turkish_apostrophe.py [chemin_vers_res]   # defaut : app/src/main/res
"""
import os
import re
import sys

KEYS = (
    "result_more_reasons_pro",
    "result_advanced_locked_title",
    "result_advanced_locked_body",
    "result_redirect_chain_pro",
    "signal_google_web_risk",
)

res_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join("app", "src", "main", "res")

# Correctif ciblé pour le turc
tr_path = os.path.join(res_dir, "values-tr", "strings.xml")
tr_fixed = False
if os.path.isfile(tr_path):
    src = open(tr_path, encoding="utf-8").read()
    # On ne corrige que si l'apostrophe n'est PAS deja echappee.
    # Pattern : Pro'ya non precede de backslash.
    new_src = re.sub(r"(?<!\\)Pro'ya", r"Pro\\'ya", src)
    if new_src != src:
        open(tr_path, "w", encoding="utf-8").write(new_src)
        tr_fixed = True
        print("[CORRIGÉ] %s : apostrophe Pro'ya -> Pro\\'ya" % tr_path)
    else:
        print("[DÉJÀ OK] %s : aucune apostrophe non echappee dans 'Pro'ya'" % tr_path)
else:
    print("[ABSENT]  %s : fichier introuvable" % tr_path)

# Audit complet : signaler toute apostrophe ASCII non echappee dans nos 5 cles,
# sur toutes les locales (au cas ou).
print()
print("== Audit AAPT2-safe sur les 5 cles du Paquet 1 ==")
total_issues = 0
import glob
for f in sorted(glob.glob(os.path.join(res_dir, "values*", "strings.xml"))):
    raw = open(f, encoding="utf-8").read()
    loc = os.path.basename(os.path.dirname(f))
    issues = []
    for line in raw.splitlines():
        for k in KEYS:
            if 'name="%s"' % k not in line:
                continue
            m = re.search(r'>([^<]*)</string>', line)
            if not m:
                continue
            val = m.group(1)
            # Une apostrophe ASCII non echappee, sans guillemets doubles autour.
            if re.search(r"(?<!\\)'", val) and not (val.startswith('"') and val.endswith('"')):
                issues.append((k, val))
    if issues:
        total_issues += len(issues)
        print("  [À CORRIGER] %s :" % loc)
        for k, val in issues:
            print("       %s -> %s" % (k, val))

if total_issues == 0:
    print("  Tout est propre : aucune apostrophe ASCII non echappee dans les 5 cles.")
else:
    print("\nIl reste %d chaine(s) a corriger manuellement (echapper l'apostrophe par \\')." % total_issues)
    sys.exit(1)