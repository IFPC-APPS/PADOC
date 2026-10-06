#!/usr/bin/env bash
#
# Ouvre un accès aux applications de la VM, le temps d'une démonstration.
#
# Pourquoi un tunnel
# ------------------
# La VM n'est pas exposée à Internet : le réseau de l'établissement ne laisse
# entrer que le port 22. Un tunnel SSH emprunte ce seul port autorisé et rend
# les applications visibles dans le navigateur de la machine qui le lance —
# sans rien contourner, sans rien demander à personne.
#
# Il ne remplace pas l'ouverture du 443 : seule la personne qui lance le tunnel
# y a accès. C'est un outil de démonstration et de recette, pas de production.
#
# Usage :
#   ./scripts/demo-vm.sh              puis Ctrl+C pour refermer
#   ./scripts/demo-vm.sh --domaines   affiche en plus la ligne /etc/hosts
#
# Prérequis : la clé SSH autorisée sur la VM (ssh-copy-id vmadmin@…).

set -u

VM="${VM_HOTE:-vmadmin@138.102.157.119}"
P_PADOC="${P_PADOC:-8080}"
P_CIDER="${P_CIDER:-8081}"
P_PROXY="${P_PROXY:-8000}"

libre() {
  ! (cat < /dev/null > "/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

for couple in "$P_PADOC PADOC" "$P_CIDER CiderScope" "$P_PROXY proxy"; do
  set -- $couple
  if ! libre "$1"; then
    echo "Le port $1 ($2) est déjà pris sur cette machine." >&2
    echo "Relancer avec un autre port, par exemple : P_PADOC=9080 $0" >&2
    exit 1
  fi
done

echo "Connexion à $VM…"
if ! ssh -o BatchMode=yes -o ConnectTimeout=10 "$VM" true 2>/dev/null; then
  echo "Connexion impossible." >&2
  echo "Vérifier le réseau, et que la clé est autorisée :" >&2
  echo "    ssh-copy-id $VM" >&2
  exit 1
fi

cat <<FIN

  ┌──────────────────────────────────────────────────────────────┐
  │  Applications de la VM, accessibles dans ce navigateur       │
  ├──────────────────────────────────────────────────────────────┤
  │  PADOC        http://localhost:$P_PADOC
  │  CiderScope   http://localhost:$P_CIDER
  │  Proxy        http://localhost:$P_PROXY
  └──────────────────────────────────────────────────────────────┘

  Ctrl+C pour refermer le tunnel.

FIN

if [ "${1:-}" = "--domaines" ]; then
  cat <<FIN
  Pour montrer les vrais noms de domaine plutôt que « localhost »,
  ajouter cette ligne à /etc/hosts (sudo requis), puis ouvrir
  http://padoc.ifpc.eu:$P_PROXY :

      127.0.0.1  padoc.ifpc.eu ciderscope.ifpc.eu

  À retirer après la démonstration, sinon ces noms resteront
  détournés vers cette machine une fois le site réellement en ligne.

FIN
fi

# -N : pas de commande distante, seulement les redirections.
# ExitOnForwardFailure : échouer franchement plutôt que d'ouvrir un tunnel
# muet dont on ne comprendrait pas, en réunion, pourquoi il ne sert rien.
exec ssh -N \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -L "$P_PADOC:localhost:3000" \
  -L "$P_CIDER:localhost:3001" \
  -L "$P_PROXY:localhost:80" \
  "$VM"
