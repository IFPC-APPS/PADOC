#!/usr/bin/env bash
#
# État des lieux de la VM avant tout déploiement. Lecture seule : ce script
# n'installe rien, ne modifie rien.
#
# Il répond à quatre questions, dans l'ordre où elles bloquent :
#
#   1. Peut-on installer quoi que ce soit ? (accès sortant)
#   2. Y a-t-il la place ? (volumes)
#   3. A-t-on les droits ? (sudo, LVM)
#   4. Qu'est-ce qui est déjà là ?
#
# Usage :
#   ssh vmadmin@138.102.157.119 'bash -s' < scripts/vm-etat-des-lieux.sh

echo "══════════════════════════════════════════════════════════════"
echo " ÉTAT DES LIEUX — $(hostname) — $(date '+%d/%m/%Y %H:%M')"
echo "══════════════════════════════════════════════════════════════"

echo
echo "── 1. ACCÈS SORTANT ──────────────────────────────────────────"
echo "   Bloquant absolu : sans lui, ni paquets, ni images, ni"
echo "   fonctionnement de l'assistant documentaire."
for cible in \
  "deb.debian.org:80|dépôts de paquets" \
  "archive.ubuntu.com:80|dépôts Ubuntu" \
  "registry-1.docker.io:443|images Docker" \
  "github.com:443|code source" \
  "generativelanguage.googleapis.com:443|modèle de langage" \
  "api.resend.com:443|envoi de courriels"
do
  hote="${cible%%|*}"; libelle="${cible##*|}"
  h="${hote%%:*}"; p="${hote##*:}"
  if timeout 6 bash -c "cat < /dev/null > /dev/tcp/$h/$p" 2>/dev/null; then
    printf "   %-42s OK\n" "$libelle ($h:$p)"
  else
    printf "   %-42s BLOQUÉ\n" "$libelle ($h:$p)"
  fi
done
echo "   Résolution DNS : $(getent hosts github.com >/dev/null 2>&1 && echo OK || echo ÉCHEC)"
echo "   Proxy déclaré  : ${http_proxy:-${HTTP_PROXY:-aucun}}"

echo
echo "── 2. ESPACE DISQUE ──────────────────────────────────────────"
echo "   Besoin : ~2 Go pour le corpus et le modèle, plus les images"
echo "   de conteneurs (comptez 10 à 15 Go dans /var)."
df -h --output=target,size,used,avail,pcent / /var /opt /home /tmp 2>/dev/null \
  | sed 's/^/   /'
echo
echo "   Espace non alloué dans le groupe de volumes :"
sudo -n vgs --noheadings -o vg_name,vg_size,vg_free 2>/dev/null | sed 's/^/   /' \
  || echo "   (sudo non disponible sans mot de passe — à vérifier à la main)"

echo
echo "── 3. DROITS ─────────────────────────────────────────────────"
if sudo -n true 2>/dev/null; then
  echo "   sudo sans mot de passe : OUI"
else
  echo "   sudo sans mot de passe : NON (gêne l'automatisation)"
fi
echo "   Groupes               : $(id -nG)"
echo "   Extension de volumes  : $(command -v lvextend >/dev/null && echo "lvextend présent" || echo "lvextend ABSENT")"

echo
echo "── 4. OUTILS DÉJÀ INSTALLÉS ──────────────────────────────────"
for outil in docker "docker compose" git nginx caddy psql java python3 node; do
  case "$outil" in
    "docker compose") v=$(docker compose version 2>/dev/null | head -1) ;;
    *)               v=$($outil --version 2>/dev/null | head -1) ;;
  esac
  printf "   %-16s %s\n" "$outil" "${v:-absent}"
done

echo
echo "── 5. SYSTÈME ────────────────────────────────────────────────"
echo "   $(. /etc/os-release && echo "$PRETTY_NAME")  —  noyau $(uname -r)"
echo "   Mémoire : $(free -h | awk '/^Mem:/ {print $2" total, "$7" disponible"}')"
echo "   Processeurs : $(nproc)"
echo "   Démarrée depuis : $(uptime -p 2>/dev/null)"

echo
echo "── 6. PORTS EN ÉCOUTE ────────────────────────────────────────"
sudo -n ss -lntp 2>/dev/null | sed 's/^/   /' || ss -lnt 2>/dev/null | sed 's/^/   /'

echo
echo "══════════════════════════════════════════════════════════════"
echo " À LIRE EN PREMIER : la section 1. Si les dépôts de paquets"
echo " et le registre Docker sont bloqués, rien ne peut être"
echo " installé et le reste du diagnostic est sans objet."
echo "══════════════════════════════════════════════════════════════"
