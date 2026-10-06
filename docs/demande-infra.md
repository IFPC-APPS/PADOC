# Demande à l'équipe infrastructure — ouverture du port 443 sur `vm-pkglinux-467`

> Brouillon à relire avant envoi. Les points marqués **[à compléter]** demandent
> une décision de notre côté, pas de l'infrastructure.
>
> Cette version remplace une demande antérieure plus large : l'application est
> désormais installée et fonctionne sur la VM, les questions de stockage et
> d'accès sortant sont réglées. **Il ne reste qu'une seule demande.**

---

**Objet :** Ouverture du port 443 — VM `vm-pkglinux-467` (demande Ariane D0227666)

Bonjour,

La plateforme PADOC est installée et fonctionne sur la VM `vm-pkglinux-467`
(`138.102.157.119`). Tous les services répondent correctement en local sur la
machine. Il ne manque qu'une chose pour que les utilisateurs puissent y accéder :
l'autorisation du trafic entrant.

## 1. Ce que nous demandons

| Besoin | Détail |
|---|---|
| **TCP/443** entrant | HTTPS, depuis Internet |
| **TCP/80** entrant | uniquement pour la redirection vers 443 et la validation du certificat Let's Encrypt |

**Un seul port de service.** Nous avons installé un reverse proxy sur la VM :
toutes les applications sont servies derrière le 443, aiguillées par nom de
domaine. Il n'y a donc aucun autre port à ouvrir, ni maintenant, ni pour les
services que nous ajouterons ensuite.

## 2. Ce que nous avons déjà vérifié de notre côté

Afin que la demande soit précise, voici l'état constaté sur la machine :

| Vérification | Résultat |
|---|---|
| Pare-feu local (`ufw`) | **inactif** |
| Règles `iptables` chaîne INPUT | **politique ACCEPT**, aucune règle de blocage |
| Écoute des services | `0.0.0.0` sur les ports concernés |
| Accès depuis Internet, port 22 | **passe** |
| Accès depuis Internet, ports 80 / 443 | **ne passe pas** |

Rien ne bloque sur la machine : le filtrage se situe en amont, dans le réseau.

**Accès sortant** : nous avons également vérifié qu'il fonctionne (dépôts de
paquets, registre d'images, services applicatifs externes). Aucune demande de
notre part sur ce point.

## 3. Noms de domaine

Le domaine `ifpc.eu` est géré par Nordnet, et nous y créerons deux
enregistrements pointant vers `138.102.157.119` :

```
padoc.ifpc.eu        A    138.102.157.119
ciderscope.ifpc.eu   A    138.102.157.119
```

Le site institutionnel `ifpc.eu` et son `www` ne sont pas concernés : ils
pointent ailleurs et restent inchangés.

## 4. Origines autorisées

L'application s'adresse à des **producteurs et techniciens de la filière
cidricole, extérieurs à l'établissement**, répartis sur le territoire et sans
adresse IP fixe. Une restriction par adresse source n'est donc pas praticable.

**[à compléter : confirmer que l'ouverture à Internet est acceptable, ou nous
indiquer la contrainte à respecter]**

## 5. Certificat TLS

Le reverse proxy (Caddy) sait obtenir et renouveler seul un certificat
Let's Encrypt, à condition que le port 80 soit joignable depuis Internet le temps
de la validation.

**Question :** préférez-vous que nous procédions ainsi, ou l'établissement
fournit-il ses propres certificats ? Dans ce second cas, nous les installerons
sur la VM.

## 6. Reverse proxy imposé ?

**Question :** un équipement intermédiaire est-il imposé en amont de la VM, ou
celle-ci peut-elle assurer elle-même la terminaison TLS ? Notre installation
actuelle suppose la seconde option ; nous l'adapterons si nécessaire.

## 7. Sauvegarde

La VM n'est, à notre connaissance, couverte par aucun dispositif de sauvegarde.
L'application tiendra des comptes utilisateurs et des registres d'analyses de
pasteurisation ayant valeur de pièces de maîtrise sanitaire.

**Question :** la machine peut-elle être intégrée à votre dispositif de
sauvegarde ? À quelle fréquence, avec quelle rétention, et selon quelle
procédure de restauration ? Nous souhaitons pouvoir réaliser un essai de
restauration.

Nous restons disponibles pour toute précision.

Cordialement,

**[à compléter : nom, fonction, service]**

---

## Notes internes — ne pas envoyer

**La demande tient maintenant en une ligne : ouvrir le 443.** C'est délibéré.
Une demande courte et étayée a plus de chances d'aboutir qu'une liste de
souhaits. Le reverse proxy a précisément été installé pour qu'il n'y ait qu'un
port à demander.

**Ce qui a déjà été réglé et sort donc de la demande :**

- le stockage — volumes étendus nous-mêmes (`/var` 59 Go, `/opt` 20 Go), 91 Go
  restent disponibles ;
- l'accès sortant — vérifié fonctionnel, aucune restriction constatée ;
- l'installation — Docker, PostgreSQL, les quatre services de PADOC et
  CiderScope tournent et sont contrôlés sains.

**Ce qui reste de notre côté, une fois le port ouvert :**

1. créer les deux enregistrements DNS chez Nordnet ;
2. retirer `auto_https off` du `Caddyfile` et les préfixes `http://` — Caddy
   obtient alors les certificats tout seul ;
3. basculer la fédération sur les noms de domaine : `FEDERATION_ISSUER` et
   `PADOC_REDIRECT_URI` doivent passer en `https://`, et
   `PADOC_ALLOW_INSECURE_HTTP` doit **disparaître** ;
4. ajouter `Strict-Transport-Security`, volontairement absent tant que l'on sert
   en clair.

**À trancher avant envoi :** le contact technique déclaré, et s'il faut
mentionner la machine de préproduction maintenant ou plus tard.
