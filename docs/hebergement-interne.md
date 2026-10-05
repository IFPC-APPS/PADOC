# Héberger PADOC en interne — ce que cela suppose

> Document de décision, destiné à l'IFPC. Il ne défend pas une option contre une
> autre : il énonce ce que l'hébergement interne demande, pour que le choix soit
> fait en connaissance de cause.

---

## 1. De quoi on parle

PADOC fonctionne aujourd'hui sur des plateformes externes qui prennent en charge,
sans que personne n'ait à s'en occuper : la mise en ligne, les certificats de
sécurité, le redémarrage après incident, et le retour à une version antérieure en
cas de problème.

Héberger en interne, c'est reprendre ces tâches à sa charge. La machine devient la
vôtre — et son entretien aussi.

**Ce n'est ni mieux ni moins bien en soi.** C'est un arbitrage entre maîtrise et
charge de travail.

## 2. Ce que l'IFPC y gagne

**La souveraineté sur l'hébergement.** Les données — comptes, registres d'analyses
de pasteurisation — résident sur une machine de l'établissement, sous son contrôle
et sa juridiction. C'est la préoccupation qui avait déjà été exprimée à propos du
corpus documentaire.

**L'indépendance vis-à-vis de comptes personnels.** Aujourd'hui, l'application vit
sur des comptes ouverts au nom d'une personne. Un incident administratif sur l'un
d'eux — cela s'est déjà produit — suffit à bloquer les mises à jour. Une
infrastructure de l'établissement met fin à cette fragilité.

**La prévisibilité des coûts.** Pas d'abonnement externe, pas de facturation à
l'usage.

## 3. Ce que cela demande à l'IFPC

### 3.1 Des autorisations réseau

Détail technique dans les documents joints. En résumé, trois choses que
l'établissement doit accorder :

| | Sans quoi |
|---|---|
| Rendre le site accessible depuis Internet | aucun producteur ne peut s'y connecter |
| Autoriser l'application à appeler des services extérieurs | l'assistant documentaire reste muet |
| Permettre d'ajuster l'espace disque de la machine | l'application ne tient pas dessus |

Les deux premières ne dépendent pas de nous.

### 3.2 Un engagement d'exploitation, dans la durée

C'est le point le plus souvent sous-estimé. Une machine interne demande, tous les
mois et indéfiniment :

- **les mises à jour de sécurité du système** — une machine exposée à Internet qui
  n'est pas mise à jour devient une vulnérabilité pour le réseau de
  l'établissement, pas seulement pour l'application ;
- **le renouvellement des certificats**, si l'établissement ne l'automatise pas ;
- **la vérification des sauvegardes** — une sauvegarde jamais restaurée n'est pas
  une sauvegarde ;
- **la surveillance** : savoir que le service est tombé avant que les utilisateurs
  ne le signalent.

**Question à trancher : qui porte cette charge ?** Aujourd'hui, une seule personne
connaît l'application. Si elle est absente, personne ne peut ni diagnostiquer ni
remettre en route. L'hébergement interne ne crée pas ce problème, mais il
l'aggrave : là où une plateforme externe redémarre seule, une machine interne
attend qu'on s'en occupe.

### 3.3 Une seconde machine, de test

**C'est la demande la plus importante de ce document.**

L'application a connu deux interruptions en deux jours, dont une de plusieurs
heures. Dans les deux cas, la modification avait été validée sur un poste de
développement : elle s'est comportée autrement une fois en production, pour des
raisons qu'aucun test local ne pouvait révéler.

Une machine de préproduction — modeste, accessible uniquement depuis
l'établissement — aurait intercepté les deux. Sans elle, chaque mise à jour reste
un pari, et l'hébergement interne rend ce pari plus coûteux, puisqu'il n'y a plus
de retour arrière automatique.

Si l'établissement ne peut fournir qu'une machine correctement configurée, nous
recommandons que ce soit **celle de test**.

### 3.4 Les services externes qui subsistent

L'hébergement interne ne supprime pas toute dépendance. Restent :

| Service | Usage | Remplaçable ? |
|---|---|---|
| Modèle de langage | rédaction des réponses de l'assistant | oui, par un modèle interne — voir §5 |
| Envoi de courriels | validation de compte, réinitialisation de mot de passe | oui, par le serveur de messagerie de l'établissement |
| Nom de domaine | adresse publique du site | à fournir par l'établissement |

Ces éléments demandent des comptes **au nom de l'établissement**, pas d'une
personne.

## 4. Ce que l'IFPC y perd

Il faut le dire clairement, parce que ce sont des protections qui existent
aujourd'hui et qui disparaîtraient :

**Le retour arrière automatique.** Les plateformes actuelles conservent la version
précédente et y reviennent en un clic. En interne, cela se reconstruit — c'est
faisable, ce n'est pas gratuit.

**Le redémarrage automatique après incident.** Une application qui s'arrête à 3 h
du matin redémarre seule aujourd'hui. Demain, elle attendra qu'on la relance.

**La vérification avant bascule.** Les plateformes actuelles peuvent ne router les
utilisateurs vers une nouvelle version que si celle-ci répond correctement. Cette
protection se réinstalle en interne, mais il faut y penser.

**La mise à l'échelle.** Sans objet au volume actuel — quelques dizaines
d'utilisateurs — mais à garder en tête.

## 5. Une décision à prendre en même temps, pas après

La question de l'hébergement et celle du **modèle de langage** sont la même
question.

L'assistant documentaire fait aujourd'hui appel à un service externe pour rédiger
ses réponses. Si l'établissement souhaite à terme que ce traitement se fasse
également en interne — ce qui a été évoqué au titre de la souveraineté — cela
change complètement le dimensionnement de la machine : il faut alors compter une
carte graphique, ou beaucoup plus de mémoire et de processeurs.

**Une machine dimensionnée pour l'application seule ne pourra pas accueillir un
modèle interne plus tard.** Il faut donc trancher maintenant :

- **Option A — hébergement de l'application uniquement.** Machine modeste. Les
  réponses de l'assistant continuent de transiter par un service externe.
- **Option B — hébergement complet, modèle compris.** Machine nettement plus
  puissante, coût supérieur, exploitation plus exigeante. Souveraineté totale.

Nous recommandons de commencer par l'option A et de mesurer, avant de décider de
l'option B. Le passage de l'une à l'autre se prépare, mais ne s'improvise pas.

## 6. Conditions que nous posons

Pour que cette migration se fasse sans mettre le service en péril :

1. **Rien n'est coupé avant validation.** L'hébergement actuel reste en service
   jusqu'à ce que la nouvelle installation ait été vérifiée de bout en bout,
   reprise des données comprise.
2. **Les autorisations réseau sont obtenues par écrit avant les travaux.** Préparer
   un déploiement qu'on ne pourra pas tester n'a pas de sens.
3. **Une personne de l'établissement est identifiée** comme interlocuteur pour
   l'exploitation de la machine — mises à jour, sauvegardes, incidents.
4. **Les comptes des services restants sont ouverts au nom de l'établissement**,
   et leurs accès partagés entre au moins deux personnes.

## 7. Ce que nous recommandons

**Faire la migration, mais dans cet ordre :**

1. obtenir les réponses réseau ;
2. mettre en place la machine de test et y valider l'installation complète ;
3. basculer la production, l'ancienne restant disponible quelques semaines ;
4. n'arrêter l'ancienne qu'après une période de fonctionnement sans incident.

**Et ne pas faire deux changements à la fois.** Migrer l'hébergement et
internaliser le modèle de langage dans le même mouvement multiplierait les causes
possibles au premier problème rencontré.

---

## Documents joints

- `demande-infra.md` — demande d'ouverture réseau sur la machine actuelle
- `demande-vm-cible.md` — spécification technique d'une machine adaptée
- `spec_serveur.md` — état de la machine mise à disposition
