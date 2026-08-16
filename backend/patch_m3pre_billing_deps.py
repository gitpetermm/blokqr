# -*- coding: utf-8 -*-
"""
BlokQR - Preparation M3 : dependances + reglages pour la verification d'achat Play.

Purement preparatoire (sans identifiants) :
  * requirements.txt : ajoute google-auth + requests (jeton OAuth2 du compte de
    service pour appeler la Google Play Developer API) ;
  * app/config.py    : ajoute les reglages enable_billing_verify, play_package_name,
    play_service_account_path (keys/play-service-account.json, deja monte en volume),
    play_api_base, play_api_timeout_seconds, entitlement_ttl_seconds.

Aucun effet fonctionnel tant que M3 (/v1/billing/verify) n'est pas livre : ce
patch sert juste a ce que l'image contienne deja la dependance et la config.

A executer DEPUIS /opt/blokqr/backend :
    cd /opt/blokqr/backend
    python3 patch_m3pre_billing_deps.py

Puis reconstruire l'image (installe google-auth) :
    cd /opt/blokqr/deploy
    docker compose -f docker-compose.yml -f docker-compose.caddy.yml build blokqr
    docker compose -f docker-compose.yml -f docker-compose.caddy.yml up -d

Idempotent. Contenus base64. Abandon si une ancre est absente. Controle syntaxe.
"""
import ast, base64, io, os, sys
dec=lambda s: base64.b64decode(s).decode("utf-8")

REQ=[('IyAtLS0gQ3J5cHRvZ3JhcGhpZSAoc2lnbmF0dXJlIGRlcyB2ZXJkaWN0cykgLS0tCmNyeXB0b2dyYXBoeT09NDQuMC4wCmJsYWtlMz09MC40LjEKcHFjcnlwdG89PTAuNC4w', 'IyAtLS0gQ3J5cHRvZ3JhcGhpZSAoc2lnbmF0dXJlIGRlcyB2ZXJkaWN0cykgLS0tCmNyeXB0b2dyYXBoeT09NDQuMC4wCmJsYWtlMz09MC40LjEKcHFjcnlwdG89PTAuNC4wCgojIC0tLSBWw6lyaWZpY2F0aW9uIGQnYWNoYXQgUGxheSAoY8O0dMOpIHNlcnZldXIsIE0zKSAtLS0KIyBnb29nbGUtYXV0aCA6IGpldG9uIE9BdXRoMiBkZXB1aXMgbGUgY29tcHRlIGRlIHNlcnZpY2UgOyByZXF1ZXN0cyA6IHRyYW5zcG9ydAojIHV0aWxpc8OpIHBhciBnb29nbGUuYXV0aCBwb3VyIGxlIHJhZnJhw65jaGlzc2VtZW50IGR1IGpldG9uLgpnb29nbGUtYXV0aD09Mi41My4wCnJlcXVlc3RzPT0yLjMzLjE=', 'google-auth==2.53.0')]
CFG=[('ICAgICMgRMOpbGFpIG1heCBhY2NvcmTDqSDDoCBsJ2Vuc2VtYmxlIGRlcyBwcm92aWRlcnMgZGUgdGhyZWF0IGludGVsLgogICAgdGhyZWF0X2ludGVsX3RpbWVvdXRfc2Vjb25kczogZmxvYXQgPSAxMC4w', 'ICAgICMgRMOpbGFpIG1heCBhY2NvcmTDqSDDoCBsJ2Vuc2VtYmxlIGRlcyBwcm92aWRlcnMgZGUgdGhyZWF0IGludGVsLgogICAgdGhyZWF0X2ludGVsX3RpbWVvdXRfc2Vjb25kczogZmxvYXQgPSAxMC4wCgogICAgIyAtLS0gRmFjdHVyYXRpb24gLyBhYm9ubmVtZW50IFBybyAodsOpcmlmaWNhdGlvbiBzZXJ2ZXVyIFBsYXksIE0zKSAtLS0tLS0tLQogICAgIyBBY3RpdmUgbGEgdsOpcmlmaWNhdGlvbiBkJ2FjaGF0IGPDtHTDqSBzZXJ2ZXVyIChHb29nbGUgUGxheSBEZXZlbG9wZXIgQVBJKS4KICAgIGVuYWJsZV9iaWxsaW5nX3ZlcmlmeTogYm9vbCA9IEZpZWxkKGRlZmF1bHQ9VHJ1ZSkKICAgICMgTm9tIGRlIHBhY2thZ2UgZGUgbCdhcHAgcHVibGnDqWUgc3VyIGxlIFBsYXkgU3RvcmUuCiAgICBwbGF5X3BhY2thZ2VfbmFtZTogc3RyID0gRmllbGQoZGVmYXVsdD0iY29tLmJsb2txci5hcHAiKQogICAgIyBDb21wdGUgZGUgc2VydmljZSBHb29nbGUgKEpTT04pIGTDqXBvc8OpIGRhbnMga2V5cy8gKG1vbnTDqSBlbiB2b2x1bWUpLgogICAgIyBORSBKQU1BSVMgY29tbWl0dGVyIGNlIGZpY2hpZXIgOiBjJ2VzdCB1biBzZWNyZXQuCiAgICBwbGF5X3NlcnZpY2VfYWNjb3VudF9wYXRoOiBzdHIgPSBGaWVsZChkZWZhdWx0PSJrZXlzL3BsYXktc2VydmljZS1hY2NvdW50Lmpzb24iKQogICAgIyBCYXNlIGRlIGwnQVBJIEFuZHJvaWQgUHVibGlzaGVyIChzdWJzY3JpcHRpb25zdjIuZ2V0KS4KICAgIHBsYXlfYXBpX2Jhc2U6IHN0ciA9IEZpZWxkKGRlZmF1bHQ9Imh0dHBzOi8vYW5kcm9pZHB1Ymxpc2hlci5nb29nbGVhcGlzLmNvbSIpCiAgICAjIETDqWxhaSBtYXggZCd1biBhcHBlbCDDoCBsJ0FQSSBQbGF5LgogICAgcGxheV9hcGlfdGltZW91dF9zZWNvbmRzOiBmbG9hdCA9IEZpZWxkKGRlZmF1bHQ9MTAuMCkKICAgICMgRHVyw6llIGRlIHZhbGlkaXTDqSBob3JzLWxpZ25lIGRlIGwnZW50aXRsZW1lbnQgc2lnbsOpIMOpbWlzIGF1IGNsaWVudAogICAgIyAoNyBqb3VycyBwYXIgZMOpZmF1dCkgOiBib3JuZSBsYSBmZW7DqnRyZSBhdmFudCByZS12w6lyaWZpY2F0aW9uIHNlcnZldXIuCiAgICBlbnRpdGxlbWVudF90dGxfc2Vjb25kczogaW50ID0gRmllbGQoZGVmYXVsdD02MDQ4MDAp', 'play_service_account_path')]

def apply_edits(path, edits, check_syntax):
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
    if check_syntax:
        try: ast.parse(src)
        except SyntaxError as e:
            print("  [ABORT] %s : syntaxe invalide -> NON ecrit (%s)"%(path,e)); return False
    io.open(path,"w",encoding="utf-8").write(src)
    print("  [PATCH] %s : modifie"%path); return True

def main():
    ok=True
    ok&=apply_edits("requirements.txt", REQ, check_syntax=False)
    ok&=apply_edits("app/config.py", CFG, check_syntax=True)
    if not ok: sys.exit(1)
    print("\n>>> Termine. Reconstruisez l'image (installe google-auth) puis redemarrez.")

if __name__=="__main__":
    main()
