# Fédération d'identité — IFPC comme fournisseur d'identité

> Spécification, **implémentée le 29/09/2026** (étapes 1 à 7 du §12). Le §12 porte l'état de
> chaque étape ; les encadrés « vérifié à l'implémentation » signalent les points où la
> réalité a corrigé ce document.

> Faire de la plateforme PADOC/IFPC un **fournisseur d'identité** : un compte créé ici
> permet de se connecter aux autres outils du domaine cidricole — à commencer par la
> plateforme d'analyse sensorielle — sans y recréer d'identifiants. IFPC tient le rôle que
> tient Google quand un site propose « Se connecter avec Google ».

## 1. Le problème

Il existe aujourd'hui deux outils développés séparément : la plateforme PADOC (ce dépôt) et
une plateforme d'**analyse sensorielle** construite sur une autre pile technique. Les
intégrer dans une seule application n'est pas souhaitable : les bases de code sont
distinctes, et un couplage fort ferait porter à chaque outil les régressions et les
contraintes de test de l'autre.

Mais l'utilisateur, lui, est la même personne physique. En l'état, il doit créer deux
comptes, retenir deux mots de passe, et l'administrateur doit valider deux fois la même
personne. Chaque outil supplémentaire multiplie ce coût.

**Ce qu'on veut découpler :** l'identité (qui est cet utilisateur ?) du métier (que
fait-il ?). L'identité devient un service partagé ; le métier reste dans chaque outil.

## 2. Ce qu'on construit — et ce qu'on ne construit pas

**On construit** un fournisseur d'identité conforme à OpenID Connect sur le Core API
Spring Boot, qui expose des endpoints publics et documentés.

**On ne construit pas** d'intégration spécifique à la plateforme d'analyse sensorielle. On
ne connaît pas sa base de code, et c'est très bien : le point d'un standard est que chaque
plateforme cliente choisisse **elle-même** sa bibliothèque et son mode d'intégration, en
lisant le document de découverte. Nous publions un contrat, pas un SDK.

C'est la différence entre « fédérer » et « intégrer » :

| | Intégration | Fédération (retenu) |
|---|---|---|
| Couplage | Chaque plateforme connaît IFPC | Chaque plateforme connaît *OIDC* |
| Nouvelle plateforme | Développement des deux côtés | Enregistrement d'un client, zéro code IFPC |
| Bibliothèques côté client | À écrire | `oidc-client-ts`, NextAuth, Spring Security, `authlib`… au choix |
| Surface de test IFPC | Croît à chaque plateforme | Constante |

## 3. Pourquoi OpenID Connect, et pas un jeton maison

La tentation est d'exposer un `POST /api/auth/verify` qui dit oui ou non sur un jeton IFPC.
C'est plus court à écrire, et c'est un mauvais calcul :

- **La sécurité d'un protocole d'authentification ne s'improvise pas.** Les attaques
  connues (rejeu du code d'autorisation, `redirect_uri` ouvert, confusion de destinataire,
  fixation de session, substitution de jeton) ont des contre-mesures normalisées et
  éprouvées. Un protocole maison les redécouvre une par une, en production.
- **Un standard transfère le travail au client.** Avec un document de découverte, la
  plateforme d'analyse s'auto-configure. Avec une API maison, il faut lui écrire un guide,
  puis le maintenir, puis le réexpliquer à chaque nouvelle plateforme.
- **Le jeton n'est pas la seule question.** Déconnexion propagée, révocation, rotation de
  clés, consentement, expiration : OIDC répond à tout cela. Un `verify` maison ne répond
  qu'au premier cas facile.

**Choix retenu : Spring Authorization Server**, le module officiel du projet Spring. Il
s'ajoute à la `SecurityFilterChain` existante sans la remplacer.

Alternative écartée — **Keycloak** : il impose soit de migrer la table `users` hors de notre
Postgres, soit d'écrire un connecteur de fédération d'utilisateurs pour aller la lire. Or
nos comptes, le hachage des mots de passe, le flux de validation `PENDING` et les envois
Resend vivent déjà dans le Core API. Déplacer l'identité ailleurs coûterait plus que de
publier des endpoints là où les utilisateurs sont déjà.

## 4. Le blocage : la signature symétrique ne peut pas fédérer

**C'est le point à régler avant tout le reste.**

L'authentification actuelle repose sur un JWT **HS256 à secret symétrique partagé** : le
même `JWT_SECRET` signe côté Spring (`JwtService.getSignInKey()`) et vérifie côté FastAPI
(`backend/auth.py`).

En HS256, **vérifier c'est signer**. Donner `JWT_SECRET` à la plateforme d'analyse pour
qu'elle valide nos jetons, c'est lui donner le pouvoir d'en **forger** : un
`{"sub": "…", "role": "ROLE_ADMIN"}` bien formé serait indistinguable d'un jeton légitime.
Et le risque se cumule : chaque plateforme fédérée devient un point depuis lequel tout IFPC
peut être compromis.

La fédération exige donc une **signature asymétrique** — RS256 ou ES256 :

```
        IFPC (fournisseur d'identité)          Plateforme cliente
        ┌────────────────────────────┐         ┌──────────────────┐
        │  clé privée   ── signe ──► │ jeton ──►│  clé publique    │
        │  (jamais diffusée)         │         │  ── vérifie ──►  │
        └────────────────────────────┘         │  (ne peut pas    │
                    │                          │   signer)        │
                    └── publie ──► /oauth2/jwks └──────────────────┘
```

Chaque plateforme récupère la clé **publique** sur le JWKS et met le résultat en cache. Elle
peut tout vérifier et ne peut rien émettre. C'est la propriété qui rend la fédération
possible.

Effet de bord bénéfique : le couplage actuel Spring↔FastAPI par secret partagé peut
disparaître par la même occasion (§11).

## 5. Architecture

### 5.1 Où vit le fournisseur d'identité — et pourquoi pas dans FastAPI

**Entièrement dans le Core API Spring Boot. Le Calc Engine FastAPI n'a aucun rôle dans la
fédération.**

La question s'est posée : s'il ne s'agit que d'authentifier un utilisateur et de répondre à
un `GET`, FastAPI pourrait-il le faire ? Non, et pour une raison de fond : **le fournisseur
d'identité doit être le service qui détient les comptes.** Or le Core API détient la table
`users`, le hachage des mots de passe, l'écran de connexion, la validation `PENDING` par un
administrateur, la réinitialisation de mot de passe et les mails Resend.

Faire émettre les jetons d'identité par FastAPI imposerait de lui donner accès à la table
des utilisateurs — donc de dupliquer la frontière de confiance la plus sensible du système
dans un second service, pour n'y gagner aucune fonctionnalité. Le nombre d'endroits capables
d'affirmer « cet utilisateur est authentifié » doit rester égal à un.

FastAPI reste ce qu'il est : un **serveur de ressources**, consommateur de jetons. Son
statut vis-à-vis du secret partagé est traité en §11.

### 5.2 Les deux jetons — distinction à ne pas manquer

OIDC émet deux jetons qui ne se substituent pas l'un à l'autre. Les confondre est l'erreur
d'intégration la plus fréquente.

| | `id_token` | `access_token` |
|---|---|---|
| Répond à | *Qui est cet utilisateur ?* | *A-t-il le droit de faire ceci ?* |
| Destinataire (`aud`) | Le `client_id` de la plateforme | L'identifiant du serveur de ressources |
| Consommé par | La plateforme cliente, une seule fois, à la connexion | L'API appelée, à chaque requête |
| À envoyer à une API ? | **Jamais** | Oui, en `Authorization: Bearer` |
| Contenu | Identité (`sub`, `email`, nom…) | Portées et habilitations pour *cette* audience |

Un `id_token` envoyé à une API, ou un `access_token` dont on lit les claims d'identité sans
vérifier l'audience, ouvre une confusion de destinataire : un jeton émis pour la plateforme A
se retrouve accepté par la plateforme B.

### 5.3 Parcours nominal — Authorization Code + PKCE

```
  Utilisateur        Plateforme cliente              IFPC
      │                     │                         │
      │  « Se connecter     │                         │
      │    avec IFPC »  ───►│                         │
      │                     │  1. /oauth2/authorize   │
      │◄────────────────────┴──  redirection  ───────►│
      │                                               │
      │  2. session IFPC active ? sinon écran de       │
      │     connexion IFPC (existant)                 │
      │                                               │
      │  3. écran de consentement :                   │
      │     « Analyse sensorielle demande votre       │
      │       identité et votre adresse e-mail »      │
      │                                               │
      │◄──  4. retour /callback?code=…  ──────────────│
      │                     │                         │
      │                     │  5. échange du code ───►│
      │                     │◄── id_token +           │
      │                     │    access_token +       │
      │                     │    refresh_token        │
      │                     │                         │
      │                     │  6. vérification de la  │
      │                     │     signature via JWKS  │
      │                     │     (clé publique, en   │
      │                     │      cache)             │
      │                     │                         │
      │                     │  7. 1re connexion :     │
      │                     │     création du compte  │
      │                     │     local depuis les    │
      │                     │     claims (JIT, §7.4)  │
      │◄──  connecté  ──────│                         │
```

Le code d'autorisation de l'étape 4 transite par le navigateur : il est donc à usage unique,
de courte durée, et lié à PKCE (§6.3) pour qu'un code intercepté soit inutilisable.

## 6. Contrat d'intégration

*Cette section est celle à transmettre aux équipes des plateformes clientes. Elle se lit
sans rien connaître de l'intérieur d'IFPC.*

> **Ne transmettez pas ce fichier — donnez l'URL.** Le contrat est servi par l'application
> elle-même, sur `/federation/documentation`, sous une forme rédigée pour un intégrateur
> extérieur et **portant les URL réelles de l'instance** qui répond. Un partenaire n'a ni
> compte IFPC ni accès à ce dépôt ; et une copie envoyée par courriel vieillit sans que
> personne ne s'en aperçoive. La page vit dans
> `security/federation/DocumentationFederation.java`.
>
> La présente section reste la référence de conception : elle dit *pourquoi* le contrat est
> ce qu'il est, ce que la page d'intégration n'a pas à expliquer.

### 6.1 Découverte

Tout part d'une seule URL :

```
GET https://<hôte-ifpc>/.well-known/openid-configuration
```

Elle renvoie l'emplacement de tous les endpoints, les algorithmes acceptés et les portées
disponibles. **Aucune URL ne doit être codée en dur ailleurs que celle-ci** : c'est ce qui
permet à IFPC de déplacer un endpoint ou de changer d'algorithme sans casser les clients.

### 6.2 Endpoints

| Endpoint | Rôle |
|---|---|
| `/.well-known/openid-configuration` | Découverte — point d'entrée unique |
| `/oauth2/authorize` | Début du parcours : redirection de l'utilisateur |
| `/oauth2/token` | Échange du code contre les jetons ; rafraîchissement |
| `/oauth2/jwks` | Clés **publiques** de vérification |
| `/userinfo` | Claims d'identité à jour, sur présentation d'un `access_token` |
| `/oauth2/revoke` | Révocation d'un jeton de rafraîchissement |
| `/connect/logout` | Déconnexion initiée par la plateforme cliente |

### 6.3 Types de client

Le choix dépend de la plateforme cliente, pas d'IFPC. Les deux sont pris en charge.

**Client confidentiel** — la plateforme a un serveur capable de garder un secret. L'échange
du code se fait de serveur à serveur ; le secret ne touche jamais le navigateur. **À
privilégier quand c'est possible.**

**Client public** — la plateforme est une application de navigateur servie en statique, sans
serveur à elle. Aucun `client_secret` (un secret livré au navigateur n'est pas un secret) ;
la sécurité repose alors entièrement sur PKCE et la correspondance exacte de `redirect_uri`.

> **Conséquence vérifiée à l'implémentation : un client public ne reçoit pas de
> `refresh_token`.** Spring Authorization Server en refuse l'émission lorsque la méthode
> d'authentification du client est `none` — un jeton de rafraîchissement stocké dans un
> navigateur est un secret de longue durée exposé au XSS. Une application de navigateur
> renouvelle donc son accès en repassant silencieusement par `/oauth2/authorize` : la session
> IFPC étant toujours ouverte et le consentement déjà accordé, la redirection est invisible
> pour l'utilisateur. C'est le schéma recommandé aujourd'hui pour ce type de client — mais
> **il impose que la plateforme sache gérer ce renouvellement par redirection**, ce qui est
> le cas des bibliothèques OIDC courantes. Une plateforme qui veut un `refresh_token` doit
> avoir un backend, donc être un client confidentiel.

**PKCE est exigé dans les deux cas.** Il lie le code d'autorisation à la session qui l'a
demandé : un code intercepté dans un journal de serveur, un historique de navigation ou un
en-tête `Referer` est inutilisable sans le vérificateur, que seul le client d'origine
possède. Le surcoût est nul pour les bibliothèques modernes, qui le font par défaut.

Si les interfaces React et Vue mentionnées sont deux applications déployées séparément, ce
sont **deux clients** distincts, avec chacun son `client_id`, ses `redirect_uri` et ses
habilitations — et non un client partagé. Une compromission reste alors circonscrite à une
seule des deux.

### 6.4 Claims

Claims standard OIDC, selon les portées accordées :

| Claim | Portée | Remarque |
|---|---|---|
| `sub` | `openid` | **Identifiant stable et opaque.** La seule clé à utiliser pour rattacher un compte local (§7.1) |
| `email` | `email` | Peut changer au cours de la vie du compte |
| `email_verified` | `email` | À vérifier avant toute décision fondée sur l'e-mail |
| `given_name`, `family_name` | `profile` | Affichage |

Claims privés, sous espace de noms pour éviter toute collision avec de futurs claims
standard :

| Claim | Contenu |
|---|---|
| `https://ifpc.eu/claims/roles` | Habilitations de l'utilisateur **pour cette plateforme uniquement** (§7.2) |
| `https://ifpc.eu/claims/organisation` | Rattachement organisationnel déclaré — **voir l'avertissement ci-dessous** |

> ⚠️ **`organisation` est déclaratif, pas vérifié.** Le champ `companyName` est aujourd'hui
> du texte libre que l'utilisateur saisit et modifie lui-même depuis son profil
> (`AuthController.updateProfile`), sans référentiel ni contrôle. Il est utilisable pour
> **afficher** un rattachement ; il ne doit **jamais** servir de frontière d'autorisation
> côté plateforme cliente — sinon n'importe qui accède aux données d'une organisation en
> recopiant son nom dans son profil. Fonder un cloisonnement par organisation suppose
> d'abord un référentiel d'organisations validé côté IFPC, hors périmètre de cette spec.

### 6.5 Ce que la plateforme cliente doit vérifier

À chaque jeton reçu, sans exception :

1. **Signature** valide contre une clé du JWKS, sélectionnée par le `kid` de l'en-tête.
2. **Algorithme** attendu (RS256/ES256), issu de la configuration et non de l'en-tête du
   jeton. Accepter l'algorithme annoncé par le jeton lui-même — en particulier `none` —
   est une vulnérabilité classique.
3. **`iss`** égal à l'émetteur IFPC attendu.
4. **`aud`** contenant son propre identifiant, et **pas** celui d'une autre plateforme.
5. **`exp`** non dépassé (une tolérance d'horloge de quelques dizaines de secondes est
   d'usage).
6. **`nonce`** identique à celui envoyé à l'étape `authorize`, pour l'`id_token`.

Les bibliothèques OIDC conformes font tout cela ; le risque est de les court-circuiter en
décodant le jeton « juste pour lire l'e-mail ». Un JWT décodé sans vérification est une
chaîne de caractères fournie par le client, donc sans valeur.

## 7. Changements sur le modèle de données IFPC

### 7.1 Un `sub` immuable — à faire dès le premier jour

Aujourd'hui le sujet du jeton est l'adresse e-mail : `User.getUsername()` renvoie `email`.

En OIDC, `sub` doit être **opaque et stable à vie**. Si l'utilisateur change d'adresse, la
plateforme cliente perd le lien avec toutes ses données locales — ou, pire, rattache son
compte à un homonyme si l'ancienne adresse est réattribuée.

**À ajouter :** un `User.externalId` de type UUID, émis comme `sub`. L'e-mail redevient un
claim ordinaire parmi les autres.

**Pourquoi c'est bloquant et non reportable :** dès qu'une plateforme cliente a rattaché des
comptes locaux à un `sub`, le changer casse ces rattachements de façon irréversible sans
migration coordonnée chez le client. C'est l'un des rares choix de cette spec qu'on ne peut
pas corriger après la mise en service.

Le cloisonnement interne, qui repose sur l'e-mail (`Tenant.currentEmail()`), n'est pas
concerné et peut migrer plus tard, à son rythme. Seul le `sub` **exposé** doit être un UUID
dès l'origine.

### 7.2 Des habilitations par plateforme

`Role` est aujourd'hui un enum global unique : `PENDING`, `USER`, `EXPERT`, `ADMIN`.

Or ces rôles ont un sens **strictement IFPC**. `EXPERT` débloque les paramètres de barème
thermique s'écartant du référentiel scientifique (`auth.py`, `verify_advanced_access`) — cela
ne veut rien dire sur une plateforme d'analyse sensorielle. Propager tel quel un
`ROLE_EXPERT` conduit soit à accorder des privilèges dénués de sens, soit à laisser chaque
plateforme réinterpréter le rôle à sa façon, ce qui place la décision d'autorisation hors de
tout contrôle.

**À ajouter :** une table d'habilitation reliant un utilisateur, un client et ses rôles
*pour ce client*. Le jeton émis pour une audience ne porte que les rôles de cette audience.

Conséquence assumée : accorder un accès à une nouvelle plateforme devient un **acte
d'administration explicite**. C'est voulu — un compte IFPC ne doit pas ouvrir silencieusement
l'accès à tout outil qui rejoint la fédération.

### 7.3 Enregistrement des clients

Chaque plateforme cliente est déclarée côté IFPC avec : `client_id`, type (confidentiel ou
public), `redirect_uri` autorisées, portées autorisées, durées de vie.

**`redirect_uri` en correspondance exacte, jamais de motif à joker.** C'est le vecteur
classique de vol de code d'autorisation : un joker trop large permet de détourner la
redirection vers un domaine contrôlé par un attaquant, avec un code valide.

### 7.4 Provisioning à la première connexion

Aucun compte n'existe sur la plateforme d'analyse sensorielle : il n'y a donc rien à
reprendre. Le compte local se crée à la première connexion fédérée, à partir des claims
(*just-in-time provisioning*), avec le `sub` comme clé de rattachement.

Le **rattachement de comptes existants** (*account linking*) est hors périmètre. Si le besoin
apparaît plus tard, une mise en garde à conserver : rattacher automatiquement un compte local
à un compte IFPC sur simple égalité d'adresse e-mail est une **prise de contrôle de compte**
dès lors que l'e-mail n'est pas prouvé vérifié des deux côtés. Un tel rattachement exige
`email_verified` et une confirmation explicite de l'utilisateur.

## 8. Le flux PENDING doit couvrir le parcours OAuth

`AuthenticationService` refuse aujourd'hui la connexion d'un compte dont le rôle est
`PENDING` ou qui n'est pas activé — l'inscription crée le compte en `PENDING`, et un
administrateur le promeut.

**Si `/oauth2/authorize` ne rejoue pas ce contrôle, la fédération devient un contournement de
la validation manuelle :** je m'inscris sur IFPC, je reste `PENDING`, et j'entre malgré tout
par la plateforme d'analyse. Le refus doit se produire *avant* l'émission du code
d'autorisation, avec un message explicite — un compte en attente de validation n'est pas une
erreur d'identifiants, et le dire évite un ticket de support à chaque inscription.

## 9. Sécurité

| Point | État actuel | Cible |
|---|---|---|
| Signature | HS256, secret partagé | RS256/ES256, clé privée non diffusée, JWKS public |
| Session IFPC | jeton en `localStorage` + cookie lisible par JS | cookie `HttpOnly`, `Secure`, `SameSite` |
| CORS | `allowedOrigins("*")` | liste explicite d'origines |
| `redirect_uri` | sans objet | correspondance exacte, enregistrée par client |
| Rotation de clés | sans objet | deux clés se recouvrant, sélection par `kid` |

Deux points méritent d'être explicités.

**La session du fournisseur d'identité change de valeur.** Le jeton est aujourd'hui lisible
par JavaScript (`localStorage` et cookie non `HttpOnly`, cf. `frontend/lib/api.ts`). Pour une
application isolée, c'est déjà une exposition en cas de XSS. Pour un fournisseur d'identité,
c'est autre chose : la session IFPC permet d'obtenir des jetons pour **toutes** les
plateformes fédérées. Le même défaut ne coûte plus une application, mais l'ensemble.

**La rotation de clés doit être prévue avant d'en avoir besoin.** Une clé de signature se
remplace un jour — par hygiène, ou en urgence. Le JWKS publie donc plusieurs clés
identifiées par `kid`, l'ancienne restant exposée le temps que les jetons émis avec elle
expirent et que les caches clients se rafraîchissent. Sans ce recouvrement, toute rotation
déconnecte tout le monde.

## 10. Durées de vie et révocation

Le jeton actuel vit 24 h côté Spring (`jwt.expiration`) et son cookie 7 jours côté
navigateur. Ces durées sont trop longues pour un jeton d'accès fédéré : un jeton volé reste
utilisable d'autant plus longtemps, et rien ne permet de l'arrêter.

| Jeton | Durée indicative | Révocable |
|---|---|---|
| `access_token` | 5 à 15 min | non — sa brièveté en tient lieu |
| `id_token` | idem | sans objet (consommé une fois) |
| `refresh_token` | jours à semaines | **oui**, via `/oauth2/revoke` |

La combinaison jeton d'accès court + jeton de rafraîchissement révocable donne ce qu'un JWT
longue durée ne peut pas donner : **une reprise de contrôle**. Désactiver un compte, ou
retirer son habilitation sur une plateforme, prend effet au prochain rafraîchissement — au
pire quelques minutes — au lieu d'attendre l'expiration d'un jeton de 7 jours.

Le jeton de rafraîchissement est **à rotation** : chaque usage en émet un nouveau et invalide
le précédent, si bien qu'un jeton rejoué est refusé (`invalid_grant`). Un rejeu signale un
vol.

> **Correction apportée après implémentation.** Cette section prévoyait un jeton de
> rafraîchissement à rotation *pour un client public*. C'est impossible : aucun jeton de
> rafraîchissement n'est émis à un client public (§6.3). La rotation ne concerne donc que les
> clients confidentiels — pour lesquels elle est bien active et vérifiée.

## 11. Hors périmètre

- **Le secret partagé Spring↔FastAPI.** Il reste strictement interne : les deux services
  sont dans la même frontière de confiance, et le Calc Engine n'a pas de rôle dans la
  fédération (§5.1). Le faire passer au JWKS est recommandé à terme — un seul émetteur, plus
  aucun secret symétrique dans le système — mais c'est un chantier indépendant, qui touche
  aussi le scellement des résultats de calcul (`signer_resultat`), lequel utilise le même
  secret pour un usage différent. À traiter séparément, pas en préalable.
- **Le rattachement de comptes existants** (§7.4) : rien à reprendre aujourd'hui.
- **Le référentiel d'organisations** qui permettrait un cloisonnement inter-plateformes par
  entreprise (§6.4).
- **La fédération entrante** — se connecter à IFPC *via* un tiers (Google, France Connect…).
  L'architecture ne l'interdit pas ; ce n'est pas le besoin.
- **SCIM** ou toute synchronisation d'annuaire : la création à la première connexion suffit
  au volume visé.

## 12. Étapes de mise en œuvre

L'ordre importait : les deux premières étapes sont irréversibles une fois des clients en
service.

| | Étape | État | Où |
|---|---|---|---|
| 1 | **`externalId` UUID sur `User`**, émis comme `sub` (§7.1) | fait | `User`, reprise des comptes existants dans `DatabaseSeeder.repairFederationSchema` |
| 2 | **Signature asymétrique** et publication du JWKS (§4) | fait | `GestionnaireCles`, table `cles_signature` |
| 3 | **Spring Authorization Server** : découverte, `authorize`, `token`, `jwks`, `userinfo` | fait | `ConfigurationFederation` |
| 4 | **Contrôle `PENDING`** sur le parcours d'autorisation (§8) | fait | `User.isEnabled()` + `ControleAccesPlateforme` |
| 5 | **Habilitation par client**, rôles par audience (§7.2) | fait | `HabilitationPlateforme`, `PersonnalisationJeton`, `FederationAdminController` |
| 6 | **Consentement** et enregistrement du client (§7.3) | fait | page de consentement fournie par le serveur ; `AmorceClients` |
| 7 | **Durcissement** : session `HttpOnly`, allowlist CORS, durées de vie (§9, §10) | fait | voir la réserve ci-dessous |
| 8 | **Transmission du contrat** (§6) à l'équipe de la plateforme d'analyse sensorielle | à faire | — |

**Réserve sur l'étape 7.** Le cookie de session du fournisseur d'identité est bien
`HttpOnly` — c'est la session de servlet, et c'est elle qui porte le parcours OAuth, donc le
point sensible du §9 est couvert. En revanche **le jeton de l'API PADOC reste en
`localStorage`** côté frontend Next.js : ce chantier touche `lib/api.ts`, `lib/store.ts`,
`middleware.ts` et chaque page, il porte un risque de régression sur une application en
service, et il n'est pas nécessaire au fonctionnement de la fédération. À traiter comme un
lot distinct.

**Ce qui a été vérifié de bout en bout**, sur PostgreSQL 16, avec un client public et un
client confidentiel :

- découverte, JWKS, `authorize`, consentement, `token`, `userinfo`, `revoke` ;
- `sub` = `external_id` et non l'adresse e-mail ; `iss`, `aud`, `alg=RS256`, `kid` conformes ;
- signature revérifiée **hors de l'application**, avec la seule clé publique du JWKS ;
- rôles émis = ceux de la plateforme destinataire ; `ROLE_ADMIN` IFPC absent des jetons ;
- refus avant émission du code : compte `PENDING`, puis compte sans habilitation ;
- retrait d'habilitation ⇒ autorisation refusée au parcours suivant ;
- rotation du jeton de rafraîchissement, et rejeu de l'ancien refusé (`invalid_grant`) ;
- rotation de clé : les deux clés restent publiées, les jetons antérieurs restent vérifiables ;
- non-régression de l'API PADOC (connexion, profil, administration, cuves, lots, historique).

## 13. Risques

| Risque | Portée | Atténuation |
|---|---|---|
| `sub` figé trop tôt sur l'e-mail | élevée — irréversible | étape 1 avant toute autre |
| Secret symétrique diffusé « en attendant » | critique | jamais : la clé publique n'est pas optionnelle |
| Rôles IFPC propagés sans traduction | moyenne | habilitation par client (§7.2) |
| `PENDING` contourné par OAuth | élevée | contrôle avant émission du code (§8) |
| `organisation` pris pour une frontière de sécurité côté client | élevée | avertissement explicite dans le contrat (§6.4) |
| Client décodant les jetons sans vérifier | élevée, hors de notre contrôle | §6.5 dans le contrat ; audience distincte par client |
