#!/usr/bin/env bash
# Cloisonnement réseau du bac à sable BlokQR.
#
# Le service détone du contenu hostile : il DOIT pouvoir joindre Internet, mais
# JAMAIS le réseau privé de l'hôte (RFC1918) — sinon une évasion du sandbox
# permettrait de pivoter vers d'autres services (ex. Blokpass) sur le même hôte
# ou le même LAN.
#
# Ce script ajoute des règles iptables dans la chaîne DOCKER-USER pour bloquer,
# depuis le sous-réseau du conteneur, toute destination privée — en autorisant
# le DNS. À exécuter en root APRÈS « docker compose up » (le réseau doit exister).
#
# Usage : sudo bash harden-egress.sh            (détecte le sous-réseau)
#         sudo bash harden-egress.sh 172.20.0.0/16   (forcer le sous-réseau)
set -euo pipefail

NET_NAME="${NET_NAME:-deploy_blokqr-net}"

if [[ "${1:-}" != "" ]]; then
  SUBNET="$1"
else
  SUBNET=$(docker network inspect "$NET_NAME" \
    -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}' 2>/dev/null || true)
fi
if [[ -z "${SUBNET:-}" ]]; then
  echo "Sous-réseau introuvable. Lancez d'abord docker compose, ou passez-le en argument." >&2
  exit 1
fi
echo "Cloisonnement du sous-réseau $SUBNET"

add() { iptables -C DOCKER-USER "$@" 2>/dev/null || iptables -I DOCKER-USER "$@"; }

# Autoriser le DNS (sinon plus aucune résolution).
add -s "$SUBNET" -p udp --dport 53 -j RETURN
add -s "$SUBNET" -p tcp --dport 53 -j RETURN

# Bloquer toutes les destinations privées (ordre : règles insérées en tête).
for cidr in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 169.254.0.0/16 127.0.0.0/8; do
  add -s "$SUBNET" -d "$cidr" -j DROP
done

echo "Règles posées. Vérifier : iptables -L DOCKER-USER -n --line-numbers"
echo "Persistance : installez 'iptables-persistent' (Debian/Ubuntu) ou recréez au boot."
