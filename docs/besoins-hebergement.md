# Besoins d'hébergement PADOC

Deux voies possibles : obtenir une VM de l'INRAE, ou louer chez un hébergeur.
Ce document donne les besoins à demander dans le premier cas, et les offres
comparées dans le second.

Relevé du 30/09/2026.

---

## 1. Ce qu'il faut demander à l'INRAE

| Besoin | Valeur demandée | Pourquoi |
|---|---|---|
| **Mémoire vive** | 16 Go | ~6 Go en usage courant ; l'assistant documentaire charge à lui seul 1,2 Go de modèle en mémoire. La marge sert au déploiement, pendant lequel deux versions coexistent |
| **Processeurs** | 4 vCPU | l'analyse documentaire calcule sur processeur, sans carte graphique |
| **Stockage** | 100 Go, **en un seul espace** | corpus + poids du modèle ≈ 1,5 Go, images de conteneurs, base de données, journaux |
| **Système** | Ubuntu LTS | identique à la VM déjà mise à disposition |
| **Exposition entrante** | TCP/443 depuis Internet, TCP/80 pour la redirection | les utilisateurs sont des producteurs extérieurs à l'établissement |
| **Nom DNS + certificat TLS** | à fournir par l'établissement | ou autorisation d'utiliser Let's Encrypt |
| **Accès sortant HTTPS** | autorisé | l'assistant interroge un service externe ; sans cela il reste muet |
| **Droits d'administration** | sudo, extension des volumes | éviter un ticket à chaque ajustement |
| **Sauvegarde** | fréquence, rétention, et **test de restauration** | l'application tient des registres à valeur de maîtrise sanitaire |
| **Machine de test** | 8 Go / 2 vCPU / 50 Go, accès interne seulement | deux interruptions en deux jours l'auraient évitée |

> **Le découpage du stockage compte plus que son volume.** La VM actuelle offre
> 200 Go dont 19 seulement sont utilisables, avec une racine déjà pleine à 79 %.
> Demander « 100 Go » ne suffit pas : demander **un seul espace de 100 Go**.

---

## 2. Serveur ou VM ?

**Une VM.**

Un serveur physique dédié n'apporterait rien : la charge est de quelques dizaines
d'utilisateurs, le système resterait à administrer soi-même, et on perdrait
l'instantané, le redimensionnement et la restauration rapide qu'offre la
virtualisation.

**Le seul cas qui changerait la réponse :** si l'IFPC décide de faire tourner le
modèle de langage en interne, il faudrait une machine à carte graphique — ni un
serveur classique, ni un VPS standard, mais une instance GPU, à un tout autre
prix. Cet arbitrage doit donc être tranché **avant** de commander.

---

## 3. Hébergeurs, si l'INRAE ne peut pas fournir

| Offre | vCPU | RAM | Stockage | Prix HT/mois | Nationalité |
|---|---:|---:|---|---:|:---:|
| **OVHcloud VPS-4** | 8 | **24 Go** | 200 Go NVMe | **19,96 €** | 🇫🇷 |
| OVHcloud VPS-3 | 6 | 12 Go | 100 Go NVMe | 10,40 € | 🇫🇷 |
| OVHcloud VPS-2 | 4 | 8 Go | 75 Go NVMe | 7,21 € | 🇫🇷 |
| Scaleway GP1-XS | 4 | 16 Go | bloc en supplément | ~67,75 € | 🇫🇷 |
| Scaleway PRO2-XS | 4 | 16 Go | bloc en supplément | ~81,90 € | 🇫🇷 |
| Infomaniak Cloud | 4 | 12 Go | 250 Go | 29,00 € | 🇨🇭 |
| **Blue** (ex BT-Blue) | sur mesure | sur mesure | sur mesure | **sur devis** | 🇫🇷 |
| ~~o2switch~~ | — | — | — | — | 🇫🇷 |

### Recommandation

**OVHcloud VPS-4**, soit environ **240 € HT par an**. Il dépasse tous les besoins
— 24 Go au lieu de 16, 200 Go de stockage — avec sauvegarde quotidienne et trafic
illimité.

Scaleway est trois à quatre fois plus cher pour des caractéristiques moindres, et
son stockage se facture à part. Infomaniak est suisse, donc hors Union
européenne : à écarter si l'argument de souveraineté est central.

Une seconde machine de test tiendrait sur un **VPS-2 à 7,21 € HT/mois**.

### Deux cas particuliers

**o2switch — à écarter, malgré les apparences.** Leur « Offre Unique » à 1,86 €
HT/mois annonce 12 threads, 48 Go de RAM et du NVMe illimité : c'est de
l'**hébergement mutualisé**, pas un VPS. Pas d'accès root, donc ni Docker, ni
machine virtuelle Java, ni serveur PostgreSQL à nous. C'est excellent pour un
site WordPress, inutilisable pour PADOC, qui fait tourner quatre services
conteneurisés et sa propre base. Leur seule offre à ressources dédiées est un
*Managed Bare-Metal* autour de 149 €/mois — hors de proportion avec le besoin.

**Blue (ex BT-Blue) — le plus intéressant sur la souveraineté.** Opérateur
français, datacenters à **Rennes et Nantes** avec plan de continuité entre les
deux sites, plus de 10 000 serveurs en production. Certifié **ISO 27001** et
**HDS** (hébergement de données de santé) — un niveau d'exigence supérieur à
celui d'un VPS grand public.

Pas de tarif public : c'est une offre B2B sur devis, avec infogérance. C'est à la
fois sa faiblesse — on ne peut pas comparer sans demander — et sa force : pour un
institut public soucieux de souveraineté, un prestataire français certifié,
joignable et contractualisé pèse plus lourd qu'un tarif au mois. **À contacter si
l'IFPC veut un hébergement externe mais refuse l'autohébergement**
(02 30 30 00 00 / contact@bt-blue.com).

### Deux mises en garde

**Les prix ont fortement augmenté en 2026**, jusqu'à +87 % sur certaines offres,
à cause de l'envolée du coût de la mémoire et des SSD tirée par la demande en
intelligence artificielle. Les tarifs ci-dessus sont ceux affichés au 30/09/2026 :
les revérifier avant tout engagement, et se méfier des prix promotionnels de
première année.

**Le prix n'est pas le vrai sujet.** 240 € par an face à une VM INRAE gratuite,
l'écart est négligeable. La question est le **délai** et l'**autonomie** : si
l'ouverture réseau côté INRAE demande des mois, 20 € par mois achètent une mise
en service immédiate. À l'inverse, une machine de l'établissement règle la
question de la souveraineté sans discussion.

---

## Sources

- [OVHcloud — VPS](https://www.ovhcloud.com/fr/vps/)
- [Scaleway — tarifs instances](https://www.scaleway.com/fr/tarifs/virtual-instances/)
- [Infomaniak — Public Cloud](https://www.infomaniak.com/de/support/faq/2626/discover-infomaniak-public-cloud-and-its-competitors)
- [Blue (ex BT-Blue) — cloud souverain](https://www.bt-blue.com/)
- [o2switch — offre unique](https://www.o2switch.fr/)
- [Next — OVHcloud augmente fortement le prix de ses VPS](https://next.ink/225720/ovhcloud-augmente-fortement-le-prix-de-ses-vps-2026-et-ipv4/)
- [Siècle Digital — hausses jusqu'à 87 %](https://siecledigital.fr/2026/08/17/lia-fait-flamber-les-prix-chez-ovhcloud-certains-serveurs-vont-couter-87-plus-cher/)
- État de la VM actuelle : [`spec_serveur.md`](spec_serveur.md)
