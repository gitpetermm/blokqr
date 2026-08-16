# -*- coding: utf-8 -*-
"""
BlokQR — Correctif B1 : mise en cache du manifeste signé (anti-DoS /manifest).

Avant : /manifest re-signait en SLH-DSA (~1-2 s de CPU pur-Python) à CHAQUE
requête, et la route est exemptée de rate-limit -> DoS CPU non authentifié +
blocage de l'event-loop async.

Après : la signature est mise en cache et re-faite uniquement si les clés
publiées changent (rotation) ou si le dernier manifeste date de plus de 6 h
(cadence alignée sur le cache client). Validité du manifeste ramenée à 48 h
-> fenêtre de rejeu courte. Le champ issued_at reste frais (<= 6 h).

Pourquoi ce n'est PAS un risque de rejeu : l'anti-rejeu réel est porté par le
NONCE du verdict (/v1/analyze), pas par le manifeste. Le manifeste est protégé
par la racine SLH-DSA épinglée + le contrôle not_after côté client. Le client
ne vérifie pas issued_at et n'envoie pas de nonce sur /manifest : re-signer par
requête ne changeait qu'un champ non vérifié -> aucun gain, seulement le DoS.

Modifie :
  * app/security/key_manifest.py  -> import threading + cache + get_or_build()
  * app/api/routes.py             -> /manifest appelle get_or_build()

À exécuter DEPUIS /opt/blokqr/backend :
    cd /opt/blokqr/backend
    python3 patch_b1_manifest_cache.py

Idempotent. Ancres ASCII (robuste à l'encodage). Contrôle de syntaxe avant écriture.
"""
import ast
import io
import sys

CACHE_INIT = (
'''        # Cache du manifeste signé : la signature SLH-DSA (pure-Python) coûte
        # ~1-2 s. On ne re-signe que si les clés publiées changent (rotation)
        # ou à cadence fixe. Évite un DoS CPU sur /manifest et le blocage de
        # l'event-loop async.
        self._lock = threading.Lock()
        self._cache: Optional["KeyManifest"] = None
        self._cache_key: Optional[tuple] = None
''')

# Commence par une ligne vide (1 ligne vide entre methodes) et finit par un
# saut de ligne apres "return m".
GET_OR_BUILD = (
'''
    def get_or_build(self, *, version: int, key_id: str, ed25519_pub_b64: str,
                     mldsa65_pub_b64: str, mlkem768_pub_b64: str,
                     validity_days: int = 2,
                     refresh_interval_seconds: int = 21600) -> KeyManifest:
        """Renvoie le manifeste signé en cache, ne re-signant qu'à cadence fixe.

        Re-signe uniquement si : aucun cache, les clés publiées ont changé
        (rotation), ou le dernier manifeste date de plus de
        refresh_interval_seconds (6 h par défaut, aligné sur le cache client de
        6 h). La signature SLH-DSA n'est donc jamais exécutée par requête
        (pas de DoS CPU) et issued_at reste frais (<= 6 h). Validité par
        défaut : 48 h -> fenêtre de rejeu courte.
        """
        cache_key = (version, key_id, ed25519_pub_b64, mldsa65_pub_b64,
                     mlkem768_pub_b64, validity_days)
        now = datetime.now(timezone.utc)
        with self._lock:
            cached = self._cache
            if cached is not None and self._cache_key == cache_key:
                issued_at = datetime.fromisoformat(cached.issued_at)
                if (now - issued_at).total_seconds() < refresh_interval_seconds:
                    return cached
            m = self.build(
                version=version, key_id=key_id,
                ed25519_pub_b64=ed25519_pub_b64,
                mldsa65_pub_b64=mldsa65_pub_b64,
                mlkem768_pub_b64=mlkem768_pub_b64,
                validity_days=validity_days,
            )
            self._cache = m
            self._cache_key = cache_key
            return m
''')

BUILD_END = ("            sig_slhdsa_b64=pqc.b64(sig), "
             "root_pub_b64=self.root_pub_b64,\n        )\n")


def transform_key_manifest(text):
    if "def get_or_build" in text:
        return text, False  # deja applique
    lines = text.splitlines(keepends=True)
    out = []
    for ln in lines:
        out.append(ln)
        if ln.rstrip("\n") == "import os" and "import threading\n" not in text:
            out.append("import threading\n")
        if ln.strip() == "self._pub, self._sk = self._load_or_create()":
            out.append(CACHE_INIT)
    text2 = "".join(out)
    if BUILD_END not in text2:
        raise RuntimeError("ancre build() introuvable -> abandon")
    text2 = text2.replace(BUILD_END, BUILD_END + GET_OR_BUILD, 1)
    return text2, True


def transform_routes(text):
    needle = "    m = ms.build(\n"
    if needle not in text:
        return text, False
    return text.replace(needle, "    m = ms.get_or_build(\n", 1), True


TARGETS = [
    ("app/security/key_manifest.py", transform_key_manifest),
    ("app/api/routes.py", transform_routes),
]


def main():
    err = False
    for path, fn in TARGETS:
        try:
            with io.open(path, "r", encoding="utf-8") as f:
                src = f.read()
        except FileNotFoundError:
            print("  [IGNORE] %s introuvable" % path); continue
        new, changed = fn(src)
        if not changed:
            print("  [OK   ] %s : deja applique (idempotent)" % path); continue
        try:
            ast.parse(new)
        except SyntaxError as e:
            print("  [ABORT] %s : syntaxe invalide (%s) -> NON ecrit" % (path, e)); err = True; continue
        with io.open(path, "w", encoding="utf-8") as f:
            f.write(new)
        print("  [PATCH] %s : cache du manifeste applique" % path)
    if err:
        sys.exit(1)
    print("\n>>> Termine. Reconstruisez l'image puis redemarrez le conteneur.")


if __name__ == "__main__":
    main()
