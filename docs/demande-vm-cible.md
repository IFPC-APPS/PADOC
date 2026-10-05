# Spécification d'une VM pour héberger PADOC

> Brouillon. À relire et adapter avant envoi à l'équipe infrastructure.
>
> Ce document tire les leçons de la VM `vm-pkglinux-467` : elle convient sur le
> papier, mais trois points bloquants n'avaient pas été demandés au moment du
> provisionnement. Ce sont eux qui structurent cette spécification, davantage
> que les chiffres.

---

## Ce que nous avons appris de la première VM

**1. Le découpage du disque compte plus que sa taille.** La VM dispose de 200 Go,
dont seuls 19 Go ont été découpés en espaces utilisables — avec une racine déjà
remplie à 79 % et des espaces applicatifs de moins d'un giga. Les 181 Go restants
sont là, mais il faut les attribuer avant de pouvoir s'en servir. Demander « 200 Go »
ne suffit donc pas : il faut demander une **répartition**.

**2. Un accès SSH ne dit rien de l'accès public.** Nous pouvons administrer la
machine, mais personne d'extérieur ne peut atteindre une application qui y
tournerait. Cette autorisation se demande séparément, et elle conditionne tout le
reste : sans elle, on ne peut même pas tester un déploiement de bout en bout.

**3. L'accès sortant est une fonctionnalité, pas un confort.** L'assistant
documentaire interroge un service externe pour rédiger ses réponses. Sans trafic
sortant autorisé, l'application démarre et les calculs fonctionnent, mais cette
fonction reste muette.

---

## 1. Usage prévu

Application web d'aide à la décision pour la filière cidricole, destinée à des
**producteurs et techniciens extérieurs à l'établissement**. Quatre services
cohabitent sur la machine, plus une base de données.

Volumétrie attendue : quelques dizaines d'utilisateurs simultanés au plus. Ce
n'est pas une application à fort trafic — le dimensionnement est gouverné par
l'empreinte mémoire d'un service d'analyse documentaire, pas par la charge.

## 2. Ressources

| Ressource | Demandé | Justification |
|---|---|---|
| Mémoire vive | **16 Go** | Le service d'analyse documentaire charge à lui seul ~1,2 Go de modèle en mémoire. Avec la base de données, la machine virtuelle Java et le reste, l'usage courant tourne autour de 6 Go ; 16 Go laissent la marge d'un déploiement sans coupure, où deux versions coexistent brièvement. |
| Processeurs | **4 vCPU** | L'analyse documentaire calcule sur processeur, sans carte graphique. Quatre cœurs suffisent ; huit accéléreraient la phase d'indexation initiale, qui ne se produit qu'une fois. |
| Stockage total | **100 Go** | Voir la répartition ci-dessous. |
| Système | **Ubuntu LTS** (22.04 ou 24.04) | Celui de la VM actuelle, que nous savons administrer. |

> La VM actuelle offre 32 Go de mémoire : c'est plus que nécessaire. Nous
> demandons moins, sciemment.

## 3. Répartition du stockage — le point à ne pas laisser par défaut

C'est ici que la première VM nous a piégés. Nous demandons explicitement :

| Emplacement | Taille | À quoi il sert |
|---|---:|---|
| `/` (racine) | **30 Go** | système et outils |
| `/var` | **40 Go** | images et volumes des conteneurs — le plus gros consommateur |
| `/opt` | **20 Go** | données applicatives : corpus documentaire (~350 Mo), poids du modèle (~1,2 Go), index |
| `/var/log` | **5 Go** | journaux |
| reste | non alloué | marge d'extension |

**Ou, plus simple et préférable :** une racine unique de 100 Go, sans découpage.
Un partitionnement fin n'apporte rien ici et ne fait que créer des impasses.

Nous demandons par ailleurs de **pouvoir étendre les volumes nous-mêmes** avec le
compte d'administration, sans passer par un ticket à chaque fois.

## 4. Réseau — à confirmer AVANT le provisionnement

C'est la partie qui conditionne tout. Un accord de principe avant la livraison de
la machine nous éviterait de découvrir un blocage après coup.

### Entrant

| Besoin | Détail |
|---|---|
| **TCP/443** | HTTPS, depuis Internet public |
| **TCP/80** | uniquement pour la redirection vers 443 et la validation du certificat |
| Nom DNS | un enregistrement pointant vers la machine — *[à compléter : nom souhaité]* |
| Certificat TLS | fourni par l'établissement, ou autorisation d'utiliser Let's Encrypt |

**Question à trancher avec vous :** l'accès doit-il être ouvert à Internet, ou
restreint à certaines plages ? Nos utilisateurs sont des producteurs extérieurs,
répartis géographiquement et sans adresse fixe : une restriction par adresse
source n'est pas praticable.

### Sortant

L'application appelle en HTTPS :

- une interface de modèle de langage (`generativelanguage.googleapis.com`) ;
- un service d'envoi de courriels (`api.resend.com`) ;
- les dépôts de paquets et d'images de conteneurs, pour l'installation et les
  mises à jour.

**Question :** ce trafic est-il autorisé librement, faut-il déclarer les
destinations, ou passer par un proxy ?

### Interne

Aucun besoin : les services communiquent entre eux sur la machine.

## 5. Deux environnements plutôt qu'un

**C'est la demande la plus importante de ce document, et la plus facile à
négliger.**

Nous avons connu deux interruptions de service en deux jours, dont une de
plusieurs heures, parce qu'une modification testée sur un poste de développement
s'est comportée autrement une fois déployée. Aucun environnement intermédiaire ne
permettait de le voir venir.

Nous demandons donc **une seconde machine, de préproduction**, aux mêmes
caractéristiques réseau mais nettement plus modeste :

| Ressource | Préproduction |
|---|---|
| Mémoire | 8 Go |
| Processeurs | 2 vCPU |
| Stockage | 50 Go |
| Accès public | oui, mais **restreint aux adresses de l'établissement** |

L'accès restreint suffit : seuls nos testeurs y vont. C'est aussi ce qui la rend
peu coûteuse à accorder.

Si une seule machine est possible, nous préférons **une préproduction correctement
exposée plutôt qu'une production surdimensionnée**.

## 6. Sauvegarde

L'application tient des registres d'analyses de pasteurisation qui ont valeur de
pièces de maîtrise sanitaire, ainsi que des comptes utilisateurs.

**Questions :** la machine est-elle incluse dans un dispositif de sauvegarde ? À
quelle fréquence, avec quelle rétention, et selon quelle procédure de
restauration ? Une sauvegarde qu'on n'a jamais restaurée n'en est pas une — nous
souhaitons pouvoir faire un essai de restauration.

## 7. Ce que nous installerons

Pour information, afin que vous puissiez signaler tout point contraire à vos
règles :

- Docker et Docker Compose ;
- PostgreSQL, en conteneur, données sur volume local ;
- un reverse proxy local (nginx ou Caddy), **si** la terminaison TLS n'est pas
  assurée en amont.

Aucun secret ne sera stocké dans le dépôt de code : ils seront fournis par le
mécanisme de configuration que vous recommanderez.

---

## Notes internes — ne pas envoyer

**Ordre de priorité des demandes**, si l'infra ne peut pas tout accorder :

1. **L'accès sortant.** Sans lui, une fonctionnalité disparaît. Non négociable.
2. **L'accès public entrant.** Sans lui, l'application n'a pas d'utilisateurs.
3. **La préproduction.** C'est ce qui évite de reproduire les pannes récentes.
4. Le découpage du stockage. Réparable après coup, contrairement aux trois
   premiers.
5. Les ressources. Nous en demandons déjà moins que ce dont nous disposons.

**Ne rien couper avant validation.** L'hébergement actuel reste en service jusqu'à
ce qu'un déploiement complet ait été vérifié sur la nouvelle machine, reprise des
données comprise.

**À trancher avant envoi :** le nom DNS, le contact technique déclaré, et si la
préproduction est demandée d'emblée ou dans un second temps.
