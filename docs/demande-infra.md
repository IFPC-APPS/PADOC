# Demande à l'équipe infrastructure — exposition de la VM `vm-pkglinux-467`

> Brouillon à relire et adapter avant envoi. Les points marqués **[à compléter]**
> demandent une décision de notre côté, pas de l'infra.

---

**Objet :** Ouverture réseau et prérequis de déploiement — VM `vm-pkglinux-467` (demande Ariane D0227666)

Bonjour,

Nous disposons de la VM `vm-pkglinux-467` (`138.102.157.119`, Ubuntu 22.04.5 LTS),
dont l'accès SSH fonctionne. Nous souhaitons y déployer la plateforme PADOC, aujourd'hui
hébergée sur une infrastructure externe que nous voulons quitter au profit d'une
installation interne.

Avant d'engager le déploiement, nous avons besoin de quelques confirmations.

## 1. Exposition de l'application

L'application est un service web destiné aux producteurs et techniciens de la filière
cidricole, **hors de l'établissement**. Elle doit donc être joignable depuis Internet.

Nous demandons :

| Besoin | Détail |
|---|---|
| Port entrant | **TCP/443** (HTTPS uniquement) |
| Redirection | TCP/80 ouvert uniquement pour la redirection vers 443 |
| Origines | Internet public — **[à compléter : confirmer que l'accès externe est acceptable, ou restreindre à une liste]** |
| Nom DNS | un enregistrement pointant vers la VM — **[à compléter : nom souhaité]** |
| Certificat TLS | selon ce que l'établissement fournit : certificat interne, ou autorisation d'utiliser Let's Encrypt (ce qui suppose que TCP/80 soit joignable depuis Internet pour la validation) |

Nous avons bien noté que l'accès SSH ne préjuge pas de l'accès HTTP/HTTPS.

**Question :** un reverse proxy ou un équipement intermédiaire est-il imposé en amont,
ou la VM peut-elle porter elle-même la terminaison TLS ?

## 2. Accès sortant

L'application appelle des services externes en HTTPS sortant :

- une API de modèle de langage (Google Gemini, `generativelanguage.googleapis.com`) ;
- un service d'envoi de courriels transactionnels (`api.resend.com`) ;
- les dépôts de paquets nécessaires à l'installation et aux mises à jour.

**Question :** le trafic HTTPS sortant est-il autorisé sans restriction, ou faut-il
déclarer les destinations ? Un proxy sortant est-il à configurer ?

> Ce point est bloquant : sans accès sortant, la fonction d'assistance documentaire
> ne fonctionne pas.

## 3. Stockage

La VM dispose d'environ 181 Go non alloués dans le volume group `vg1`, mais les
systèmes de fichiers actuels sont étroits pour notre usage :

| Point de montage | Taille | Disponible |
|---|---:|---:|
| `/` | 5,4 Gio | 1,1 Gio (79 % utilisé) |
| `/opt` | 920 Mio | 857 Mio |
| `/var` | 3,6 Gio | 2,0 Gio |

Nos besoins : environ **2 Go** pour un corpus documentaire et les poids d'un modèle
d'analyse, plus la base de données et les images de conteneurs.

**Question :** pouvons-nous étendre nous-mêmes les volumes logiques (`lvextend` puis
`resize2fs`) depuis le compte `vmadmin`, ou cette opération relève-t-elle de vos équipes ?
Le cas échéant, nous demandons :

- `/var` étendu à **50 Go** (images et volumes de conteneurs) ;
- `/opt` étendu à **20 Go** (données applicatives).

## 4. Sauvegarde

L'application gérera des données de production : comptes utilisateurs et registres
d'analyses de pasteurisation, ces derniers ayant valeur de pièces de maîtrise sanitaire.

**Question :** la VM est-elle incluse dans un dispositif de sauvegarde ? Si oui, à quelle
fréquence et avec quelle durée de rétention ? Sinon, quelle solution recommandez-vous ?

## 5. Ce que nous installerons

Pour information, afin que vous puissiez signaler tout point contraire à vos règles :

- Docker et Docker Compose, pour l'exécution des services ;
- PostgreSQL 16, en conteneur, données sur volume local ;
- un reverse proxy local (nginx ou Caddy) pour la terminaison TLS, **si** la
  terminaison n'est pas assurée en amont.

Nous restons disponibles pour toute précision.

Cordialement,

**[à compléter : nom, fonction, service]**

---

## Notes internes — ne pas envoyer

**Le point 2 est le chemin critique.** Une réponse négative sur l'accès sortant remet en
cause la migration du service d'assistance documentaire, pas seulement son déploiement.

**Le point 1 détermine le calendrier.** Tant que l'exposition n'est pas accordée, rien
ne sert de préparer le déploiement : on ne pourra pas le tester de bout en bout.

**Ne rien couper avant validation.** L'infrastructure actuelle reste en service jusqu'à
ce qu'un déploiement complet ait été vérifié sur la VM, y compris la reprise des données.

**Questions à trancher de notre côté avant envoi :**

1. Le nom DNS souhaité.
2. L'accès doit-il être public, ou restreint à certains réseaux ? Des producteurs
   extérieurs doivent pouvoir se connecter — a priori public.
3. Qui est le contact technique déclaré pour cette VM.
