# Besoins d'hébergement PADOC

Deux voies possibles : obtenir une VM de l'INRAE, ou louer chez un hébergeur.
Ce document donne les besoins à demander dans le premier cas, et les offres
comparées dans le second.

Relevé du 30/09/2026.

---

## 1. Ce qu'il faut demander à l'INRAE

| Besoin | Valeur demandée | Pourquoi |
|---|---|---|
| **Mémoire vive** | 16 Go | ~6 Go en usage courant ; le rag charge à lui seul 1,2 Go de modèle en mémoire. La marge sert au déploiement, pendant lequel deux versions coexistent |
| **Processeurs** | 4 vCPU | l'analyse documentaire calcule sur processeur, sans carte graphique |
| **Stockage** | 100 Go, **en un seul espace** | corpus + poids du modèle ≈ 1,5 Go, images de conteneurs, base de données, journaux |
| **Système** | Ubuntu LTS | identique à la VM déjà mise à disposition de Lucas |
| **Exposition entrante** | TCP/443 depuis Internet, TCP/80 pour la redirection | les utilisateurs sont des producteurs extérieurs à l'établissement |
| **Accès sortant HTTPS** | autorisé | l'application à interroger les services externes|
| **Droits d'administration** | sudo, extension des volumes | éviter un ticket à chaque ajustement |
| **Sauvegarde** | fréquence, rétention, et **test de restauration** | l'application tient des registres à valeur de maîtrise sanitaire |
| **Machine de test** | 8 Go / 2 vCPU / 50 Go, accès interne seulement | deux interruptions en deux jours l'auraient évitée |

> **Le découpage du stockage compte plus que son volume.** La VM de Lucas offre
> 200 Go dont 19 seulement sont utilisables, avec une racine déjà pleine à 79 %.

---


---

## 2. Hébergeurs, si l'INRAE ne peut pas fournir

| Offre | vCPU | RAM | Stockage | Prix HT/mois | Nationalité |
|---|---:|---:|---|---:|:---:|
| **OVHcloud VPS-4** | 8 | **24 Go** | 200 Go NVMe | **19,96 €** | 🇫🇷 |
| OVHcloud VPS-3 | 6 | 12 Go | 100 Go NVMe | 10,40 € | 🇫🇷 |
| OVHcloud VPS-2 | 4 | 8 Go | 75 Go NVMe | 7,21 € | 🇫🇷 |
| Scaleway GP1-XS | 4 | 16 Go | bloc en supplément | ~67,75 € | 🇫🇷 |
| Scaleway PRO2-XS | 4 | 16 Go | bloc en supplément | ~81,90 € | 🇫🇷 |
| Infomaniak Cloud | 4 | 12 Go | 250 Go | 29,00 € | 🇨🇭 |

### Recommandation

**OVHcloud VPS-4**, soit environ **240 € HT par an**. Il dépasse tous les besoins
— 24 Go au lieu de 16, 200 Go de stockage — avec sauvegarde quotidienne et trafic
illimité.

Scaleway est trois à quatre fois plus cher pour des caractéristiques moindres, et
son stockage se facture à part. Infomaniak est suisse, donc hors Union
européenne : à écarter si l'argument de souveraineté est central.

Une seconde machine de test tiendrait sur un **VPS-2 à 7,21 € HT/mois**.

---

## Sources

- [OVHcloud — VPS](https://www.ovhcloud.com/fr/vps/)
- [Scaleway — tarifs instances](https://www.scaleway.com/fr/tarifs/virtual-instances/)
- [Infomaniak — Public Cloud](https://www.infomaniak.com/de/support/faq/2626/discover-infomaniak-public-cloud-and-its-competitors)
- [Next — OVHcloud augmente fortement le prix de ses VPS](https://next.ink/225720/ovhcloud-augmente-fortement-le-prix-de-ses-vps-2026-et-ipv4/)
- [Siècle Digital — hausses jusqu'à 87 %](https://siecledigital.fr/2026/08/17/lia-fait-flamber-les-prix-chez-ovhcloud-certains-serveurs-vont-couter-87-plus-cher/)
- État de la VM actuelle : [`spec_serveur.md`](spec_serveur.md)
