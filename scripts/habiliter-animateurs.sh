#!/usr/bin/env bash
#
# Accorde à une liste de comptes IFPC l'accès à une plateforme fédérée.
#
# L'habilitation est un acte d'administration explicite : un compte IFPC
# n'ouvre pas l'accès à un outil partenaire tant que personne ne l'a décidé
# (docs/federation-identite.md §7.2). Passer par ce script plutôt que par des
# appels à la main évite deux erreurs coûteuses : oublier un animateur, et
# écrire un nom de rôle qui ne correspond pas à ceux que la plateforme attend.
#
# Idempotent : relancer le script sur un compte déjà habilité met simplement
# ses rôles à jour.
#
# Usage :
#   API=https://<core-api> ADMIN_EMAIL=... ADMIN_PASSWORD=... \
#     ./scripts/habiliter-animateurs.sh ciderscope animateur \
#       marie.durand@exemple.fr paul.martin@exemple.fr
#
#   # ou depuis un fichier, une adresse par ligne :
#   API=... ADMIN_EMAIL=... ADMIN_PASSWORD=... \
#     ./scripts/habiliter-animateurs.sh ciderscope animateur $(cat animateurs.txt)
#
#   # ou tous les comptes PADOC approuvés :
#   API=... ADMIN_EMAIL=... ADMIN_PASSWORD=... \
#     ./scripts/habiliter-animateurs.sh ciderscope animateur --tous
#
# --tous prend une PHOTOGRAPHIE de l'annuaire : les comptes créés ensuite ne
# sont pas habilités. Relancer après chaque vague d'inscriptions, ou demander
# un accès ouvert sur le client (voir docs/federation-identite.md §7.2).
#
# --tous n'inclut jamais les comptes PENDING : un compte non encore validé par
# un administrateur ne doit pas recevoir d'accès, sinon la fédération devient
# un contournement de cette validation.

set -euo pipefail

CLIENT_ID="${1:-}"
ROLES="${2:-}"
shift 2 || true
COMPTES=("$@")

if [[ -z "$CLIENT_ID" || -z "$ROLES" || ${#COMPTES[@]} -eq 0 ]]; then
    sed -n '3,25p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

: "${API:?definir API = URL publique du Core API, ex. https://padoc-api.up.railway.app}"
: "${ADMIN_EMAIL:?definir ADMIN_EMAIL}"
: "${ADMIN_PASSWORD:?definir ADMIN_PASSWORD}"

echo "Plateforme : $CLIENT_ID"
echo "Rôles      : $ROLES"

# ── Jeton d'administration ───────────────────────────────────────────────────
JETON=$(curl -fsS -X POST "$API/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token") or "")')

if [[ -z "$JETON" ]]; then
    echo "Échec de connexion administrateur — vérifier ADMIN_EMAIL / ADMIN_PASSWORD." >&2
    exit 1
fi

# ── Correspondance adresse -> identifiant, en une seule requête ──────────────
# Un appel par animateur multiplierait les allers-retours et, surtout, ne
# distinguerait pas « compte absent » de « erreur réseau ».
ANNUAIRE=$(curl -fsS "$API/api/admin/users" -H "Authorization: Bearer $JETON")

# ── --tous : tous les comptes approuvés de l'annuaire PADOC ──────────────────
if [[ "${COMPTES[0]}" == "--tous" ]]; then
    mapfile -t COMPTES < <(printf '%s' "$ANNUAIRE" | python3 -c '
import json, sys
for u in json.load(sys.stdin):
    # PENDING exclu : compte en attente de validation par un administrateur.
    if u.get("role") != "PENDING" and u.get("email"):
        print(u["email"])
')
    if [[ ${#COMPTES[@]} -eq 0 ]]; then
        echo "Aucun compte approuvé — rien à habiliter." >&2
        exit 1
    fi
fi

echo "Comptes    : ${#COMPTES[@]}"
echo

resoudre_id() {
    printf '%s' "$ANNUAIRE" | python3 -c '
import json, sys
cible = sys.argv[1].strip().lower()
for u in json.load(sys.stdin):
    if (u.get("email") or "").strip().lower() == cible:
        print(u["id"]); break
' "$1"
}

# ── Habilitations ────────────────────────────────────────────────────────────
accordes=0
absents=()

for email in "${COMPTES[@]}"; do
    id=$(resoudre_id "$email")
    if [[ -z "$id" ]]; then
        # Le compte IFPC doit exister AVANT : la fédération partage une
        # identité, elle n'en crée pas. La personne doit s'inscrire sur PADOC
        # et être approuvée par un administrateur.
        printf '  %-40s aucun compte IFPC\n' "$email"
        absents+=("$email")
        continue
    fi
    code=$(curl -fsS -o /dev/null -w '%{http_code}' \
        -X PUT "$API/api/admin/federation/utilisateurs/$id/habilitations/$CLIENT_ID" \
        -H "Authorization: Bearer $JETON" -H 'Content-Type: application/json' \
        -d "{\"roles\":\"$ROLES\"}")
    printf '  %-40s %s\n' "$email" "$code"
    [[ "$code" == "200" ]] && accordes=$((accordes + 1))
done

echo
echo "$accordes accordée(s) sur ${#COMPTES[@]}."

if [[ ${#absents[@]} -gt 0 ]]; then
    echo
    echo "Sans compte IFPC — à faire inscrire puis approuver avant de relancer :"
    printf '  %s\n' "${absents[@]}"
    exit 2
fi
