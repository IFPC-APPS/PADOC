# Spec 07 — API et interface

> **Ce module est un backend.** Il alimente le chatbot de la plateforme d'aide à la
> décision cidricole de l'IFPC. L'interface finale appartient à la plateforme ; nous
> fournissons l'API, et un rendu prêt à l'emploi que la plateforme affiche telle quelle
> ou remplace par le sien.
>
> Objectif produit inchangé : **remplacer un parcours de 2 à 10 minutes par une réponse
> en quelques secondes, sans faire perdre à l'utilisateur ses repères visuels.**

---

## 0. État d'implémentation

Ce qui suit décrivait une cible ; une partie est désormais servie.

| Élément | État | Où |
|---|---|---|
| `POST /api/ldc/ask` en SSE | **fait** | `interfaces/web/api.py`, lancé par `./serve` |
| `GET /api/ldc/illustration/{idoc}` | **fait** | idem — cache long, le corpus ne bouge qu'à la réindexation |
| `GET /api/ldc/fiche/{idoc}` | **fait** | fiche + blocs + zones + « voir aussi », en une réponse |
| `GET /api/ldc/suggestions`, `/sante` | **fait** | questions d'amorce (`config/suggestions.json`), état du service |
| `POST /api/ldc/feedback` | **fait** | append-only dans `data/retours.jsonl` (spec 08) |
| Interface | **faite, côté plateforme** | `frontend/app/assistant`, `frontend/components/assistant/` |
| `GET /api/search`, `/doc/{code}`, `/processus` | non fait | pas de besoin identifié par l'écran actuel |
| Citations natives (événement `citation`) | sans objet avec Gemini | marqueurs `[Sn]` + vérification programmatique |

Décisions tranchées par cette implémentation :

- **D9 — format attendu par la plateforme** : **JSON**. Le front est un Next.js
  qui rend lui-même ; `interfaces/web/rendu.py` n'est plus sur le chemin du
  produit, il reste l'outil d'aperçu.
- **D10 — authentification** : le jeton JWT de la plateforme est vérifié dès que
  `JWT_SECRET` est présent dans l'environnement du service. Sans clé partagée
  (développement), le service reste ouvert et l'annonce au démarrage. Chaque
  question coûte un appel payant : une route de génération ouverte sur
  l'internet est une facture ouverte.
- **Choix d'interface (§6)** : ni HTMX ni un front dédié — l'écran est une page
  de la plateforme, qui est déjà en Next.js. Le contrat d'API est le même.

Le préfixe est `/api/ldc` et non `/api` : le proxy du front distingue ainsi ce
service du moteur de calcul (`FASTAPI_URL`) et du cœur métier (`SPRING_URL`).

### Déploiement (Railway)

⚠️ **D11 — hébergement : RÉSOLUE.** Le backend de la plateforme tourne sur
Railway ; ce service y prend place comme un service de plus, avec son propre
`Dockerfile` et `railway.toml`, répertoire racine `backend/rag_ascocid`.

Trois particularités, qui ne s'inventent pas au moment du déploiement :

**1. Le corpus n'est pas dans l'image, et il s'amorce tout seul.** `data/` pèse
341 Mo (base SQLite, blobs d'images, index vectoriel) et reste hors du dépôt.
Il vit sur un **volume monté sur `/data`** ; l'image lit `ASCOCID_DATA=/data`.

Railway ne donne pas d'accès shell aux conteneurs : l'ingestion ne peut donc
pas être lancée à la main. Le point d'entrée de l'image est `./demarrer`, qui
lance `./amorcer` **en tâche de fond** puis sert immédiatement. `amorcer` ne
fait quelque chose que si le corpus est vide *et* que les identifiants d'accès
sont présents (`ASCOCID_ROOT_URL`, `ASCOCID_AUTH=basic`, `ASCOCID_USER`,
`ASCOCID_PASSWORD`) ; il enchaîne alors `probe inventaire` → `ingest collecte`
→ `ingest extraire` → `ingest indexer`, en journalisant sur la sortie standard
et dans `/data/amorcage.log`.

L'ingestion dure des dizaines de minutes : la lancer avant d'écouter ferait
échouer la sonde de santé et Railway annulerait le déploiement. Le service
répond donc tout de suite, sur un corpus encore vide — ce que `/api/ldc/sante`
annonce honnêtement, et où l'on voit le nombre de fiches grimper pendant
l'extraction.

⚠️ **Redémarrer le service une fois l'amorçage terminé.** L'index vectoriel et
la carte du corpus sont chargés au démarrage : le processus qui a lancé
l'ingestion continue de tourner sur l'état d'avant. Le journal le rappelle en
fin d'amorçage.

Les deux garde-fous sont vérifiés sur l'image : volume déjà peuplé → amorçage
ignoré, service complet (415 fiches, recherche hybride) ; volume vide sans
identifiants → message explicite, service en mode dégradé, aucun plantage.

**2. Les poids du modèle non plus.** `HF_HOME=/data/modeles` place le cache
Hugging Face sur le volume : 1,1 Go téléchargés au tout premier démarrage, puis
plus jamais. C'est ce qui garde l'image sous les 2 Go et rend les
redéploiements rapides. Corollaire : la sonde de santé doit être patiente
(`healthcheckTimeout = 600`), et **la mise en veille automatique est à
proscrire** — chaque réveil coûterait le rechargement du modèle (~15 s).

**3. Les deux familles d'adresses, sur une seule socket.** Le réseau privé de
Railway est en IPv6 ; son proxy public entre en IPv4. Aucune valeur de
`--host` ne satisfait les deux — et « :: » ne suffit pas : uvicorn ouvre alors
une socket IPv6 **exclusive**. Mesuré dans le conteneur, `bindv6only = 0`
pourtant :

```
[::1]:8100      → HTTP 200
127.0.0.1:8100  → connexion refusée      ⇒ « Application failed to respond »
```

D'où `interfaces/web/lancer.py`, qui construit la socket avec `IPV6_V6ONLY`
désactivé. `LDC_HOST=::` (défaut de l'image) signifie donc « double pile ».
Fixer `PORT=8100` rend en outre l'adresse interne déterministe :
`http://<nom-du-service>.railway.internal:8100`.

⚠️ **Pendant le chargement, le service ne répond pas encore.** L'index et le
modèle sont chargés dans le `lifespan`, avant que le port n'accepte des
connexions : le domaine renvoie 502 tant que le journal n'affiche pas
`Application startup complete`. Quelques secondes en régime normal, plusieurs
minutes au tout premier démarrage d'un volume neuf, le temps de télécharger les
poids. C'est ce que couvre `healthcheckTimeout = 600`.

**Front hébergé ailleurs (Vercel).** Le service `ldc` a alors besoin d'un
domaine public Railway, et `JWT_SECRET` cesse d'être une précaution : c'est la
seule chose entre la clé Gemini et l'internet. Deux chemins possibles pour le
navigateur, dans cet ordre de préférence :

1. **Par le proxy du front** (défaut) : `LDC_URL=https://<domaine-railway>` —
   sans numéro de port, une URL publique Railway répond en 443. Même origine
   pour le navigateur, aucun CORS, le jeton ne sort pas du domaine du front.
   ⚠️ À vérifier au premier essai : une réponse rédigée tient un flux SSE
   ouvert 6 à 20 s, et tous les proxys ne laissent pas passer une réponse aussi
   longue. Le symptôme serait une réponse qui s'interrompt en cours de
   rédaction, toujours au même délai.
2. **En direct**, si le premier chemin coupe le flux :
   `NEXT_PUBLIC_LDC_URL=https://<domaine-railway>/api/ldc` sur le front, et
   `LDC_ORIGINES=https://<domaine-du-front>` sur le service. Le navigateur
   appelle Railway sans intermédiaire ; le jeton de la plateforme part
   cross-origin, ce que CORS encadre.

Côté front, cette URL doit être fournie **au build** : les rewrites de
`next.config.mjs` sont figées dans `routes-manifest.json` à la construction
(`ARG LDC_URL` dans `frontend/Dockerfile`). Une variable d'exécution n'aurait
aucun effet — le symptôme serait une page `/assistant` qui affiche « service
injoignable » alors que le service répond. Vérification après le premier
déploiement : l'en-tête de la page doit annoncer « 415 fiches ».

Variables à déclarer sur le service :

| Variable | Valeur | Effet si absente |
|---|---|---|
| `ARGO_API_KEY` | clé ARGO (INRAE) | repli sur Gemini si sa clé est là, sinon `/ask` répond 503 et la recherche continue de fonctionner |
| `GEMINI_API_KEY` | clé du projet | utilisée seulement à défaut d'ARGO |
| `LDC_FOURNISSEUR` | vide | déduit de la clé présente — ARGO d'abord |
| `ARGO_MODELE` | `chat-gpt-oss-120b` | repli sur la même valeur |
| `ARGO_MODELE_ANALYSE` | `chat-gpt-oss-20b` | repli sur la même valeur |
| `JWT_SECRET` | **identique au Core API** | service ouvert à tous : chaque question devient un appel payant offert |
| `GEMINI_MODELE` | `gemini-3.8-flash` | **repli sur `gemini-2.5-pro`** : 4× plus cher (1,25/10 $ le million contre 0,30/2,50) et nettement plus lent. Le `.env` local masque ce défaut, il n'est pas dans l'image. |
| `GEMINI_MODELE_ANALYSE` | `gemini-3.1-flash-lite` | repli sur la même valeur — sans conséquence, mais autant l'expliciter |
| `PORT` | `8100` | Railway en attribue un ; l'adresse interne devient incertaine |
| `LDC_ORIGINES` | l'origine du front | CORS ouvert (`*`) |

**Mesuré sur l'image construite** (`docker build -t ascocid-ldc .`, puis
exécution avec le corpus monté sur `/data`) : image de **1,55 Go**, **725 Mo**
de mémoire résidente modèle chargé, **11 s** de démarrage à froid, recherche
hybride opérationnelle et illustrations servies depuis le volume. Le poste lent
reste l'appel au modèle de rédaction, pas ce service.

---

## 1. Principes d'interface

1. **La réponse d'abord, la source à côté** — jamais l'inverse. L'utilisateur vient
   chercher une information, pas une liste de résultats.
2. **Toute affirmation est cliquable.** Un élément non sourcé est un défaut visible.
3. **Le schéma reste le repère.** Chaque réponse propose le logigramme concerné, avec
   l'étape surlignée. C'est le lien avec les 10 ans d'habitude des utilisateurs.
4. **L'incertitude est affichée**, pas masquée (§4).
5. **Zéro configuration visible.** Pas de curseur « top-k », pas de choix de modèle.

---

## 2. API HTTP

### `POST /api/ask` — flux SSE

```jsonc
// requête
{
  "question": "Qui valide une dérogation fournisseur et sous quel délai ?",
  "conversation_id": "c_01H…",          // optionnel
  "filtres": {"processus": ["achats"]}, // optionnel
  "inclure_perimes": false              // défaut : false (spec 04 §7)
}
```

Événements SSE, dans l'ordre :

| Événement | Charge utile | Moment |
|---|---|---|
| `route` | `{"intention": "factuelle", "ms": 1}` | immédiat |
| `sources` | tableau de `Source` (§2.1) | dès la fin du rerank (~150 ms) — **l'utilisateur voit les sources avant le premier mot** |
| `delta` | `{"texte": "…"}` | streaming de la réponse |
| `citation` | `{"apres_car": 214, "source_idx": 0, "page": 3, "extrait": "…"}` | à chaque bloc cité |
| `illustrations` | tableau d'`Illustration` (§2.2) | **avec les sources, avant le premier mot** |
| `fin` | `{"latence": {"recherche_ms": 143, "ttft_ms": 980, "total_ms": 3610}, "confiance": "haute", "cout_eur": 0.017}` | fin |
| `erreur` | `{"code": "...", "message": "..."}` | le cas échéant |

Émettre `sources` **avant** le premier `delta` est un choix délibéré : la latence perçue
chute nettement quand quelque chose d'utile s'affiche à 150 ms, même si le texte arrive à
1,2 s.

### 2.1 Objet `Illustration`

Émis avant le texte, pour que la plateforme compose sa mise en page pendant que la
réponse arrive encore. Elle reçoit de quoi décider **sans ouvrir le fichier**.

```jsonc
{
  "fiche_idoc": 1019,
  "nature": "schema",              // schema | planche | figure
  "legende": "Étape fermentaire",
  "url": "/api/illustration/1019",
  "format": "svg",                 // svg | png
  "largeur": 1280, "hauteur": 882,
  "orientation": "paysage",        // paysage | portrait | carre
  "octets": 634880,
  "essentielle": false,
  "zones": [                       // uniquement pour nature = schema
    {"libelle": "La fermentation primaire", "cible_idoc": 1114,
     "x_pct": 25.938, "y_pct": 31.066, "taille_pct": 1.875, "active": true}
  ]
}
```

Trois natures, qui n'appellent pas le même traitement graphique :

| `nature` | Ce que c'est | Conséquence d'affichage |
|---|---|---|
| `schema` | Carte conceptuelle **vectorielle**, avec zones cliquables | Supporte n'importe quelle taille. Les `zones` permettent à la plateforme de dessiner sa propre surcouche — `active: true` marque une étape citée par la réponse. |
| `planche` | Fiche de variété : **l'image est le contenu**, il n'y a aucun texte derrière | `essentielle: true` — ne pas l'afficher, c'est ne rien montrer. À présenter en grand ou pas du tout. |
| `figure` | Illustration accompagnant un article rédigé | Décorative au sens strict : la réponse tient sans elle. |

Les coordonnées des zones sont **en pourcentage du cadre**, jamais en pixels : elles
restent justes quelle que soit la taille d'affichage choisie, sans recalcul. C'est ce
qui permet à la plateforme de traiter le schéma comme un élément graphique à part
entière plutôt que comme une image figée.

`orientation` et `octets` sont là pour la mise en page et le chargement différé — trois
paysages ne se composent pas comme deux portraits, et une planche de 1,7 Mo ne se
charge pas comme une figure de 100 Ko.

Au plus **trois illustrations par réponse**, dans l'ordre de pertinence des passages :
on suit le classement de la recherche plutôt que d'inventer un critère.

### 2.2 Objet `Source`

```jsonc
{
  "source_idx": 0,
  "document": {"code": "PR-QUA-012", "titre": "Traitement des dérogations", "type": "procedure"},
  "version": {"indice": "C", "statut": "en_vigueur", "date_application": "2024-03-01"},
  "page": 3,
  "extrait": "La dérogation est visée par le Responsable Qualité sous 48 heures…",
  "liens": {
    "pdf": "/api/doc/PR-QUA-012@C/fichier#page=3",
    "logigramme": "/api/logigramme/lg-derogations?etape=4",
    "fiche": "/api/doc/PR-QUA-012"
  },
  "rattachement": {"etape": "Viser la dérogation", "logigramme": "Dérogations fournisseurs",
                   "acteur": "Responsable Qualité", "origine": "imagemap", "confiance": 1.0}
}
```

`rattachement.origine` et `.confiance` remontent jusqu'ici depuis l'extraction (spec 03
§5.3) : c'est ce qui permet de distinguer un lien **lu** dans AsCoCid d'un lien **déduit**.

### 2.3 Autres routes

| Route | Rôle |
|---|---|
| `GET /api/search?q=&type=&processus=` | Recherche sans génération (rapide, pour la barre de recherche) |
| `GET /api/doc/{code}` | Fiche document : versions, statut, étapes qui le référencent |
| `GET /api/doc/{code}@{indice}/fichier` | Fichier d'origine (proxy, en-têtes de cache) |
| `GET /api/logigramme/{id}` | Schéma + étapes + transitions + documents liés (JSON) |
| `GET /api/processus` | Cartographie complète (alimente la navigation classique) |
| `POST /api/feedback` | `{message_id, utile: bool, motif?, commentaire?}` → alimente le jeu d'or (spec 08) |
| `GET /api/sante` | État : index, alias actif, date de dernière ingestion, anomalies ouvertes |

---

## 3. Écran principal

```
┌──────────────────────────────────────────┬──────────────────────────────┐
│  Qui valide une dérogation fournisseur ? │   ┌────────────────────────┐ │
│  ────────────────────────────────────────│   │  Dérogations fourn.    │ │
│                                          │   │                        │ │
│  La dérogation est visée par le          │   │   ①──►②──►③            │ │
│  Responsable Qualité sous 48 h après     │   │        │               │ │
│  émission. [PR-QUA-012 · C · p.3]        │   │        ▼               │ │
│                                          │   │      ┏━━━━┓  ← étape 4 │ │
│  En cas de refus, le dossier repart à    │   │      ┃ ④  ┃  surlignée │ │
│  l'étape 2. [Logigramme, étape 4→2]      │   │      ┗━━━━┛            │ │
│                                          │   │        │               │ │
│  ─────────── Sources (2) ─────────────   │   │        ▼    ⑤          │ │
│  ▸ PR-QUA-012 indice C — p. 3            │   └────────────────────────┘ │
│    étape 4 « Viser la dérogation »       │   Étape 4 · Viser la dérog.  │
│  ▸ FO-QUA-004 indice B — formulaire      │   Acteur : Resp. Qualité     │
│                                          │   Documents : PR-QUA-012,    │
│  👍 👎  · Copier · Ouvrir dans AsCoCid    │               FO-QUA-004     │
└──────────────────────────────────────────┴──────────────────────────────┘
```

Comportements :

- clic sur une citation `[PR-QUA-012 · C · p.3]` → ouvre le PDF **à la page 3**, extrait
  surligné ;
- clic sur une étape du schéma → filtre la conversation sur cette étape et liste ses
  documents ;
- le panneau de droite est **persistant** : c'est la vue « schéma » d'AsCoCid, pilotée par
  la réponse au lieu d'être parcourue à la main ;
- `Ouvrir dans AsCoCid` renvoie vers la page d'origine — l'outil ne cherche pas à
  remplacer la source de vérité.

### 3.1 Surlignage de l'étape

Le rendu superpose un cadre en SVG sur l'image du logigramme aux coordonnées
`etape.bbox`, mises à l'échelle depuis `logigramme.largeur/hauteur` (spec 02 §2.2). Si la
`bbox` est absente (extraction sans géométrie), on se rabat sur la mise en évidence
textuelle de l'étape dans la liste, sans faux surlignage.

---

## 4. Affichage de l'incertitude

| Situation | Affichage |
|---|---|
| Toutes les sources en `confiance ≥ 0.9`, statut `en_vigueur` | Rien de particulier |
| Un rattachement issu d'un VLM (`confiance < 0.9`) | Mention discrète *« lien déduit du schéma »* sur la source concernée |
| Extraction de logigramme en cas E non revue | Bandeau sur le panneau schéma : *« schéma interprété automatiquement »* |
| Aucun passage pertinent | Pas de réponse générée. Message : *« Je ne trouve pas cette information dans le référentiel. »* + 3 pistes (processus proches, documents voisins) |
| Passages contradictoires | Réponse présentant les deux versions, chacune citée |
| Mode « inclure les périmés » actif | Bandeau rouge permanent |

Le quatrième cas est le plus important. Un système qui invente une réponse plausible une
fois sur vingt sur un référentiel qualité perd la confiance de ses utilisateurs
définitivement — et il la perd **silencieusement**, parce que personne ne vérifie.

---

## 5. Retour utilisateur

Le 👎 ouvre trois cases à cocher : *réponse fausse* / *source manquante* / *hors sujet*, et
un champ libre. Chaque retour est enregistré avec la question, les sources retenues et la
réponse.

C'est le **seul mécanisme fiable** d'amélioration continue : le jeu d'or initial (spec 08)
vieillit, l'usage réel non. Les retours négatifs sont triés chaque semaine et les cas
reproductibles sont ajoutés au jeu d'évaluation.

---

## 6. Choix technique d'interface

Deux options, à trancher au jalon J4 selon les compétences disponibles :

| Option | Avantages | Coût |
|---|---|---|
| **FastAPI + HTMX + SSE + un peu de JS** (proposée) | Un seul service, un seul déploiement, pas de chaîne de build front, suffisant pour cet écran | ~3 j |
| React / Next.js | Meilleur si l'interface doit évoluer vers un vrai produit multi-écrans | ~8 j |

L'interface décrite ci-dessus tient sans difficulté dans la première option. Le contrat
d'API (§2) est identique dans les deux cas : le choix est réversible.

---

## 7. Accès et journalisation

- Authentification alignée sur celle d'AsCoCid (SSO si disponible).
- ⚠️ **D4** : si des droits différenciés existent, le filtrage se fait **dans la requête de
  recherche** (spec 04 §7), jamais au moment de l'affichage — un passage non autorisé ne
  doit jamais atteindre le modèle.
- Journalisation : question, sources retenues, latences, coût, retour utilisateur.
  Conservation limitée et documentée ; les questions peuvent contenir des éléments
  sensibles.


---

## 8. Conversation multi-tours

⚠️ **Manque identifié, non traité par la conception actuelle.** Toute la chaîne est
mono-tour : une question, une recherche, une réponse. Un chatbot n'a pas ce
fonctionnement.

Le cas concret : après *« à quelle température fermenter ? »*, l'utilisateur écrit
*« et pour les pommes douces ? »*. Cette phrase, envoyée telle quelle à la recherche,
ne récupère rien d'utile — elle ne contient ni « température » ni « fermentation ».

Trois traitements possibles, du moins au plus coûteux :

| Approche | Coût en latence | Quand la retenir |
|---|---|---|
| **Concaténation glissante** — la question précédente est préfixée à la requête de recherche | 0 ms | Défaut proposé : couvre les relances courtes, qui sont la majorité |
| **Réécriture de requête par le modèle** — « et pour les pommes douces ? » devient « température de fermentation pour les pommes douces » | +400 à 800 ms sur le chemin critique | Si l'évaluation montre un déficit de rappel sur les relances |
| **Recherche à chaque tour sur l'historique complet** | croissant | À éviter : le contexte dérive et le rappel se dégrade |

Le fil de conversation est porté par la plateforme, pas par nous : l'API reçoit
l'historique dans la requête et reste sans état. C'est ce qui permet à la plateforme de
gérer ses propres sessions, et à notre service de rester horizontalement scalable.

**La réponse précédente n'est jamais réinjectée comme source.** Seuls les passages du
Livre sont citables ; une affirmation du tour précédent n'a pas valeur de fait.

---

## 9. Contrat attendu par la plateforme

Trois familles d'appels, à confirmer avec l'équipe IFPC (voir décisions D9–D11).

| Route | Rôle | Format |
|---|---|---|
| `POST /api/ldc/ask` | La réponse, en flux | SSE (§2) |
| `GET /api/ldc/fiche/{idoc}` | Une fiche : blocs, zones si c'est une carte, renvois | JSON |
| `GET /api/ldc/illustration/{idoc}` | Le SVG ou le PNG d'origine | binaire, `Cache-Control` long — les images ne changent qu'à la réindexation |

La carte n'a pas sa route propre : ses zones arrivent avec la fiche, et
l'événement `illustrations` les porte déjà pour la réponse en cours. Un
aller-retour de moins.

Le module `interfaces/web/rendu.py` produit déjà le HTML des deux vues centrales ; les
servir revient à les brancher sur des routes.

**Le verdict de vérification est transmis à la plateforme**, jamais masqué : si une
réponse est bloquée, elle ne doit pas être affichée comme une réponse ordinaire. C'est
la plateforme qui décide de la présentation, mais elle doit disposer de l'information.

```jsonc
"fin": {
  "verification": {"bloquee": false, "alertes": [
    {"gravite": "avertissement", "code": "unite_implicite",
     "detail": "« 12 » figure bien dans un passage, mais sans l'unité « kg » accolée"}
  ]},
  "latence": {"recherche_ms": 90, "ttft_ms": 5400, "total_ms": 6100},
  "cout_usd": 0.0024
}
```
