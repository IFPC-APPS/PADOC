# PADOC - Plateforme d'aide à la Décision pour la filière Cidricole

Plateforme d'aide à la décision pour la filière cidricole française. L'outil accompagne les producteurs et techniciens dans la pasteurisation et l'analyse colorimétrique des productions (jus, cidres).

---

##  Résumé

IFPC centralise les calculs techniques critiques pour la production cidricole : calcul de la Valeur Pasteur (VP), aide au barème de pasteurisation, et mesures colorimétriques. Le portail vise à standardiser les pratiques et sécuriser la qualité des productions à l'échelle de la filière.

---

##  Fonctionnalités en cours

### Pasteurisation
Module de calcul de la Valeur Pasteur cumulée (VP) à partir de relevés de température. L'outil détermine si un lot a atteint la pasteurisation cible en fonction du produit (jus, cidre), de sa clarification (trouble/limpide), et du micro-organisme cible. Intègre un aide-barème pour estimer les temps de maintien nécessaires selon la température de consigne.

### Colorimétrie *(en développement)*
Module de mesure et d'analyse des caractéristiques colorimétriques des productions. Permet la mesure objective des références colorées et l'analyse comparative des lots pour le suivi qualité et le contrôle de production.

### Assistant du Livre de Connaissances *(nouveau)*
Chatbot adossé au Livre de Connaissances cidricoles **AsCoCid** (415 fiches, IFPC / INRAE) :
question en langage courant, réponse rédigée **avec ses sources** et le schéma
concerné, étape mise en évidence. Écran `/assistant`.

Le moteur vit dans [`backend/rag_ascocid/`](backend/rag_ascocid/) — recherche hybride
(FTS5 + embeddings locaux), rédaction par modèle, contrôle de traçabilité avant
affichage. Une question dont la réponse dépend des données de l'utilisateur (« quel
barème pour mon lot ? ») n'est pas devinée : l'assistant renvoie vers l'outil de la
plateforme qui la traite.

```bash
# 1. le service de réponse (charge l'index et le modèle d'embeddings : ~10 s)
cd backend/rag_ascocid && ./serve          # écoute sur :8100

# 2. le front le voit à travers son proxy — rien à configurer en local
cd frontend && npm run dev                 # /api/ldc/* → LDC_URL (défaut :8100)
```

Sans `GEMINI_API_KEY` dans `backend/rag_ascocid/.env`, le service démarre et sert la
recherche, mais `/api/ldc/ask` répond 503 : la page l'affiche au lieu de proposer un
chat inerte. Détail des routes : [`docs/07-api-ui.md`](backend/rag_ascocid/docs/07-api-ui.md).

**En production (Railway)** : service séparé, répertoire racine
`backend/rag_ascocid`, `Dockerfile` + `railway.toml` fournis. Trois points qui ne
s'improvisent pas — le corpus (341 Mo) vit sur un **volume monté sur `/data`** et non
dans l'image, le réseau privé de Railway impose une écoute en IPv6 (`LDC_HOST=::`,
déjà dans l'image), et `LDC_URL` doit être fournie **au build** du front, les rewrites
Next étant figées à la construction. Procédure complète :
[`docs/07-api-ui.md` §0](backend/rag_ascocid/docs/07-api-ui.md).

---

## Déploiement local

### Prérequis
- Node.js 18+
- Python 3.11+
- PostgreSQL 15+

### Installation

```bash
# 1. Cloner le repository
git clone https://github.com/thisisbriandb/ifpc.git
cd ifpc

# 2. Backend
python -m venv backend/venv
source backend/venv/bin/activate  # Windows: backend\venv\Scripts\activate
pip install -r backend/requirements.txt

# 3. Configuration
cp backend/.env.example backend/.env
# Éditer backend/.env avec vos credentials DB

# 4. Base de données
alembic upgrade head

# 5. Frontend
cd frontend
npm install

# 6. Lancement
cd ../backend
uvicorn app.main:app --reload --port 8000 &
cd ../frontend
npm run dev
```

Accès : http://localhost:3000

---

## Déploiement Vercel

### Backend (API)

```bash
cd backend
```

1. Créer un projet sur [Vercel](https://vercel.com)
2. Configurer les variables d'environnement dans l'interface Vercel :
   - `DATABASE_URL` (PostgreSQL)
   - `SECRET_KEY`
   - `ACCESS_TOKEN_EXPIRE_MINUTES`
3. Déployer :
   ```bash
   vercel --prod
   ```

### Frontend

```bash
cd frontend
```

1. Créer un projet Next.js sur Vercel
2. Configurer la variable d'environnement :
   - `NEXT_PUBLIC_API_URL` (URL du backend déployé)
3. Déployer :
   ```bash
   vercel --prod
   ```

### Configuration proxy
Dans `frontend/next.config.js`, s'assurer que le rewrites pointe vers l'URL backend :

```javascript
async rewrites() {
  return [
    {
      source: '/api/:path*',
      destination: `${process.env.NEXT_PUBLIC_API_URL}/:path*`
    }
  ]
}
```

---

## Architecture

- **Frontend** : Next.js 14 + React + TypeScript + Tailwind CSS
- **Backend** : FastAPI + SQLAlchemy + Pydantic
- **Base de données** : PostgreSQL
- **Authentification** : JWT
- **Assistant AsCoCid** : service Python séparé (`backend/rag_ascocid`, port 8100) —
  SQLite + FTS5, embeddings locaux, SSE. Séparé parce qu'il embarque un modèle
  d'embeddings et un index en mémoire dont le moteur de calcul n'a aucun besoin.

---

## Secrets et compte administrateur

Aucun secret ne doit vivre dans le dépôt. Deux règles sont désormais imposées
par le code lui-même :

* **`JWT_SECRET` est obligatoire.** Le Core API (`JwtService`) et le Calc
  Engine (`backend/auth.py`) refusent de démarrer si la variable est absente.
  Il n'existe plus de clé de repli : une clé écrite dans le dépôt est une clé
  publique, avec laquelle n'importe qui peut forger un jeton d'administrateur.
  Générer la valeur avec `openssl rand -base64 48`, et **la même** des deux
  côtés.
* **Le mot de passe administrateur initial n'est plus en dur.** Au premier
  démarrage, et uniquement si le compte n'existe pas déjà, le seeder utilise
  `PADOC_ADMIN_PASSWORD` s'il est fourni, sinon tire une valeur aléatoire
  affichée **une seule fois** dans le journal de démarrage.

> ⚠️ **Sur une base déjà en service, le compte administrateur existant n'est
> pas modifié** : le seeder ne crée le compte que s'il est absent. Un
> déploiement antérieur avec le mot de passe `admin` reste donc vulnérable
> jusqu'à ce que ce mot de passe soit changé à la main, via l'application.

---

## Cloisonnement des données (multi-tenant)

Chaque utilisateur dispose de son propre jeu de données : ses lots, ses cuves,
ses stockages, ses opérations et son historique d'analyses. Un utilisateur X
n'atteint jamais les données d'un utilisateur Y.

**Règles appliquées par le Core API (Spring) :**

* la clé de locataire est l'adresse e-mail de l'utilisateur, portée par la
  colonne `owner_email` des tables `lots` et `cuves` (et par `user_email` pour
  `operations` et `analysis_history`) ;
* toutes les routes métier exigent un jeton — seules `/api/auth/**`,
  `/api/config/**` et `/api/deploy/**` restent publiques ;
* une ressource appartenant à un autre locataire répond **404**, jamais 403 :
  son existence même n'est pas révélée ;
* l'identifiant de lot est unique **par propriétaire** et non plus globalement
  (index `ux_lots_owner_identifiant`), deux exploitations peuvent donc nommer
  un lot de la même façon.

Le point d'entrée unique est `com.ifpc.api.security.Tenant`. Pour cloisonner
par exploitation plutôt que par personne, seule sa méthode `currentEmail()` est
à modifier.

**Reprise des données antérieures** — au démarrage, `DatabaseSeeder` rattache
les lignes sans propriétaire : d'abord via le journal des opérations (auteur de
la première opération citant le lot ou la cuve), puis via les stockages. Le
reliquat non attribuable revient au compte défini par
`PADOC_LEGACY_OWNER_EMAIL` (par défaut `admin@ifpc.com`). L'opération est
idempotente : elle peut être rejouée sans risque.

> **À vérifier après la première mise en production.** Avant le cloisonnement,
> les cuves et les lots étaient communs à tous les comptes : si plusieurs
> personnes d'une même exploitation les alimentaient, la reprise les répartira
> entre elles selon qui a agi en premier. Pour tout rassembler sur un seul
> compte, exécuter après le premier démarrage :
>
> ```sql
> UPDATE lots       SET owner_email = 'exploitant@exemple.fr';
> UPDATE cuves      SET owner_email = 'exploitant@exemple.fr';
> UPDATE operations SET user_email  = 'exploitant@exemple.fr';
> ```

---

## Fédération d'identité — « Se connecter avec IFPC »

PADOC est **fournisseur d'identité OpenID Connect** pour les autres outils de la
filière : un compte créé ici ouvre l'accès aux plateformes partenaires sans y
recréer d'identifiants. IFPC tient le rôle que tient Google quand un site propose
« Se connecter avec Google ».

**Le guide d'intégration est servi par l'application elle-même** — c'est le
document à transmettre à une équipe partenaire, qui n'a ni compte IFPC ni accès à
ce dépôt :

```
http://localhost:8080/federation/documentation      # en local
https://<hôte-de-production>/federation/documentation
```

Il affiche les **URL réelles de l'instance qui répond**, lues dans sa
configuration : rien à adapter à la main. Conception et justification des choix :
[`docs/federation-identite.md`](docs/federation-identite.md).

**Trois propriétés à connaître avant d'exploiter :**

* **Les jetons fédérés sont signés en RS256**, avec une paire de clés propre,
  conservée en base (`cles_signature`) et publiée au JWKS. Elle n'a rien à voir
  avec le `JWT_SECRET` HS256 de l'API PADOC, qui reste interne au couple
  Spring/FastAPI. En HS256, vérifier c'est signer : communiquer ce secret à un
  partenaire lui donnerait le pouvoir de forger un jeton d'administrateur.
* **Le `sub` exposé est `users.external_id`** (UUID), jamais l'adresse e-mail —
  celle-ci change au cours de la vie d'un compte, et une plateforme qui y aurait
  rattaché ses données locales les perdrait. Cet identifiant **ne doit jamais
  être modifié** une fois qu'un partenaire s'en sert comme clé.
* **L'accès est accordé compte par compte.** Sans habilitation, le parcours
  d'autorisation est refusé avant l'émission du moindre code. Un compte IFPC
  n'ouvre pas silencieusement l'accès à tout outil qui rejoint la fédération.

**Activation.** Sans `FEDERATION_CLIENT_ID`, la fédération reste en sommeil : les
points d'entrée répondent, aucune plateforme n'est autorisée — un client déclaré
par défaut dans le dépôt serait un accès public. Variables dans
[`.env.example`](.env.example).

```bash
# Accorder l'accès d'un utilisateur à une plateforme, avec ses rôles CHEZ ELLE
curl -X PUT "$API/api/admin/federation/utilisateurs/$ID/habilitations/$CLIENT_ID" \
     -H "Authorization: Bearer $JETON_ADMIN" -H 'Content-Type: application/json' \
     -d '{"roles":"degustateur,referent"}'

# Retirer l'accès (effet au renouvellement suivant, 10 min au plus)
curl -X DELETE "$API/api/admin/federation/utilisateurs/$ID/habilitations/$CLIENT_ID" \
     -H "Authorization: Bearer $JETON_ADMIN"

# Renouveler la clé de signature (l'ancienne reste vérifiable 7 jours)
curl -X POST "$API/api/admin/federation/cles/rotation" \
     -H "Authorization: Bearer $JETON_ADMIN"
```

> **Le rôle IFPC n'est jamais propagé.** `EXPERT` débloque chez nous les
> paramètres de barème hors référentiel : cela n'a aucun sens sur un autre outil.
> Les rôles transmis sont ceux de la table `habilitations_plateforme`, dans le
> vocabulaire de la plateforme destinataire.



---

## Licence

Propriétaire — Institut Français des Productions Cidricoles (IFPC)
# IFPC — Portail de Décision Cidricole

Plateforme d’aide à la décision destinée à la filière cidricole française. Cet outil accompagne les producteurs et techniciens dans la maîtrise des procédés de pasteurisation et l’analyse colorimétrique des productions (jus, cidres).

---

## Objectif

Fournir un cadre fiable et reproductible pour :

* la validation des procédés de pasteurisation
* la réduction des risques microbiologiques
* la standardisation des pratiques au sein de la filière
* le suivi qualité des productions

---

## Présentation

Le portail IFPC centralise les calculs techniques critiques liés à la transformation cidricole. Il permet d’analyser des relevés de production, de vérifier l’atteinte des objectifs de traitement thermique, et d’assurer une cohérence des pratiques entre acteurs.

---

## Fonctionnalités

### Pasteurisation

Module de calcul de la Valeur Pasteur cumulée (VP) à partir de relevés de température.

Fonctionnalités principales :

* Calcul de la VP en continu
* Évaluation de la conformité d’un lot selon :

  * le type de produit (jus, cidre)
  * l’état (trouble ou limpide)
  * le micro-organisme cible
* Aide à la définition des barèmes de pasteurisation
* Estimation des temps de maintien en fonction des températures de consigne

---

### Colorimétrie (en développement)

Module d’analyse des caractéristiques colorimétriques des productions.

Objectifs :

* Mesure objective des références colorées
* Comparaison inter-lots
* Suivi qualité en production
* Détection de dérives

---

## Cas d’usage

* Validation de procédés de pasteurisation en production
* Analyse de conformité d’un lot
* Définition ou ajustement de barèmes thermiques
* Suivi qualité et homogénéité des productions
* Comparaison analytique entre lots

---

## Déploiement local

### Prérequis

* Node.js 18 ou supérieur
* Python 3.11 ou supérieur
* PostgreSQL 15 ou supérieur

---

### Installation

```bash
# 1. Clonage du dépôt
git clone https://github.com/thisisbriandb/ifpc.git
cd ifpc

# 2. Backend
python -m venv backend/venv
source backend/venv/bin/activate  # Windows : backend\venv\Scripts\activate
pip install -r backend/requirements.txt

# 3. Configuration
cp backend/.env.example backend/.env
# Renseigner les variables d’environnement (base de données, sécurité)

# 4. Base de données
alembic upgrade head

# 5. Frontend
cd frontend
npm install

# 6. Lancement des services
cd ../backend
uvicorn app.main:app --reload --port 8000 &

cd ../frontend
npm run dev
```

Accès local : `http://localhost:3000`

---

## Déploiement

### Backend (API)

```bash
cd backend
```

Étapes :

1. Créer un projet sur la plateforme de déploiement
2. Configurer les variables d’environnement :

   * `DATABASE_URL`
   * `SECRET_KEY`
   * `ACCESS_TOKEN_EXPIRE_MINUTES`
3. Lancer le déploiement

---

### Frontend

```bash
cd frontend
```

Étapes :

1. Créer un projet frontend
2. Configurer :

   * `NEXT_PUBLIC_API_URL`
3. Déployer l’application

---

### Configuration du proxy

Dans `frontend/next.config.js` :

```javascript
async rewrites() {
  return [
    {
      source: '/api/:path*',
      destination: `${process.env.NEXT_PUBLIC_API_URL}/:path*`
    }
  ]
}
```

---

## Architecture technique

* Frontend : Next.js, TypeScript, Tailwind CSS
* Backend : FastAPI, Spring Boot
* Base de données : PostgreSQL
* Authentification : JWT

---

## Licence

Propriétaire — Institut Français des Productions Cidricoles (IFPC)
