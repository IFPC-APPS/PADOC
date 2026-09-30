# VM de déploiement

## Informations générales

| Élément                  | Valeur                               |
| ------------------------ | ------------------------------------ |
| Nom de la VM             | `vm-pkglinux-467`                    |
| Adresse IP               | `138.102.157.119`                    |
| OS                       | Ubuntu 22.04.5 LTS (Jammy Jellyfish) |
| Utilisateur SSH          | `vmadmin`                            |
| Demande Ariane           | `D0227666`                           |
| Architecture de stockage | LVM                                  |
| Volume Group             | `vg1`                                |

> Le nom de la machine ne doit pas être utilisé directement pour les connexions ou la configuration des services. Utiliser l'adresse/IP ou le mécanisme de nommage fourni par l'infrastructure.

---

## Connexion SSH

Depuis une machine Linux autorisée :

```bash
ssh vmadmin@138.102.157.119
```

Lors de la première connexion, vérifier l'empreinte de la clé SSH avant d'accepter l'hôte.

Empreinte observée lors de la première connexion :

```text
ED25519 SHA256:hcwCd8t6iqWi6heXfihoxs5XJTGvPH28nSorLF7vNew
```

Après connexion, l'invite doit être similaire à :

```text
vmadmin@vm-pkglinux-467:~$
```

Pour quitter la session :

```bash
exit
```

---

## Ressources de la VM

### Mémoire

```bash
free -h
```

État observé :

```text
RAM totale       : 31 GiB
RAM utilisée     : ~341 MiB
RAM disponible   : ~30 GiB
Swap             : 1,9 GiB
Swap utilisée    : 0 B
```

La VM dispose donc d'environ **32 Go de RAM**.

---

## Stockage

La VM utilise LVM avec un volume group nommé `vg1`.

Commande :

```bash
sudo vgdisplay
```

État observé :

```text
VG Size          : ~199,52 GiB
Alloc PE / Size  : ~18,61 GiB
Free PE / Size   : ~180,91 GiB
```

Il reste donc environ **181 Go disponibles dans le volume group**.

### Systèmes de fichiers

Commande :

```bash
df -h
```

État observé :

| Point de montage |  Taille | Utilisé | Disponible |
| ---------------- | ------: | ------: | ---------: |
| `/`              | 5,4 GiB | 4,0 GiB |    1,1 GiB |
| `/boot`          | 462 MiB | 285 MiB |    149 MiB |
| `/home`          | 920 MiB | 136 KiB |    857 MiB |
| `/opt`           | 920 MiB |  44 KiB |    857 MiB |
| `/tmp`           | 920 MiB |  68 KiB |    857 MiB |
| `/var`           | 3,6 GiB | 1,5 GiB |    2,0 GiB |
| `/var/spool`     | 2,7 GiB | 136 KiB |    2,6 GiB |
| `/var/log`       | 1,8 GiB | 306 MiB |    1,4 GiB |

### Volumes logiques

Les principaux Logical Volumes sont :

```text
/dev/vg1/root
/dev/vg1/varlog
/dev/vg1/varspool
/dev/vg1/var
/dev/vg1/home
/dev/vg1/opt
/dev/vg1/tmp
/dev/vg1/swap
```

Pour les afficher :

```bash
sudo lvdisplay
```

---

## Extension des volumes

Le volume group `vg1` dispose actuellement d'environ 181 Go non alloués.

Avant toute extension, vérifier :

1. le volume logique concerné ;
2. le système de fichiers utilisé ;
3. l'espace réellement disponible dans le volume group ;
4. le besoin réel de l'application.

Exemple fourni par l'infrastructure pour agrandir `/opt` de 20 Go :

```bash
sudo lvextend -L+20G /dev/vg1/opt
```

Puis, pour un système de fichiers ext2/ext3/ext4 :

```bash
sudo resize2fs /dev/vg1/opt
```

> Ne pas exécuter ces commandes sans avoir identifié précisément le volume et le système de fichiers concernés.

---

## Vérifications utiles

### Système

```bash
hostname
cat /etc/os-release
uname -a
```

### Mémoire

```bash
free -h
```

### Stockage

```bash
df -h
lsblk -f
```

### LVM

```bash
sudo vgdisplay
sudo lvdisplay
```

### Services et ports

```bash
sudo ss -lntp
```

### Vérification des outils avant déploiement

```bash
git --version
docker --version
nginx -v
```

Ces commandes permettent de déterminer si les outils nécessaires au déploiement sont déjà installés.

---

## Réseau et filtrage

L'accès à la VM est soumis aux règles de filtrage réseau de l'infrastructure.

L'accès SSH utilise :

```text
TCP/22
```

La possibilité d'accéder à une future application dépendra des règles réseau associées à la VM.

Avant de rendre une application accessible aux utilisateurs, vérifier avec l'équipe infrastructure :

* les réseaux/IP sources autorisés ;
* le ou les ports autorisés ;
* le protocole utilisé (`HTTP` / `HTTPS`) ;
* si l'accès doit être interne uniquement ou accessible depuis l'extérieur ;
* la nécessité éventuelle d'un reverse proxy ou d'un équipement réseau intermédiaire.

Un accès SSH fonctionnel ne garantit pas qu'un accès HTTP/HTTPS sera autorisé.

---

## Sécurité

Ne jamais stocker dans le dépôt :

* mot de passe SSH ;
* clés privées SSH ;
* tokens ;
* secrets applicatifs ;
* mots de passe de base de données ;
* fichiers `.env` contenant des secrets.

Les secrets doivent être fournis via le mécanisme de configuration/secrets prévu par l'infrastructure.

---

## État initial

La VM est actuellement un environnement Ubuntu 22.04.5 LTS vierge destiné au déploiement.

État constaté :

* SSH fonctionnel ;
* environ 32 Go de RAM ;
* environ 200 Go de stockage provisionné ;
* environ 181 Go encore disponibles dans `vg1` ;
* plusieurs volumes LVM séparés ;
* `/` actuellement à environ 79 % d'utilisation ;
* aucun déploiement applicatif réalisé à ce stade.
