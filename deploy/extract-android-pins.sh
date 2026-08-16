#!/usr/bin/env bash
# Extrait les valeurs à épingler dans l'app Android depuis le backend EN LIGNE.
# À lancer depuis n'importe quelle machine ayant accès à l'API (ex. votre poste).
#
# Usage : bash deploy/extract-android-pins.sh api.blokqr.com
set -euo pipefail
HOST="${1:-api.blokqr.com}"

echo "== Récupération depuis https://$HOST =="

# 1) Racine de confiance SLH-DSA (publiée dans le manifeste signé)
ROOT=$(curl -fsS "https://$HOST/manifest" | python3 -c "import sys,json;print(json.load(sys.stdin)['root_pub_b64'])")

# 2) Empreinte SPKI du certificat TLS (épinglage OkHttp)
PIN=$(echo | openssl s_client -servername "$HOST" -connect "$HOST:443" 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der 2>/dev/null \
  | openssl dgst -sha256 -binary | openssl enc -base64)

echo
echo "# ----- À reporter dans android/.../com/blokqr/app/Config.kt -----"
echo "const val API_BASE_URL = \"https://$HOST\""
echo "const val PINNED_SLHDSA_ROOT_PUBKEY_B64 = \"$ROOT\""
echo "const val CERT_PIN_SHA256 = \"sha256/$PIN\""
echo
echo "# Ces valeurs sont PUBLIQUES (clés/empreintes d'épinglage) : OK à committer."
echo "# Le keystore de signature, lui, reste SECRET (GitHub Secrets)."
