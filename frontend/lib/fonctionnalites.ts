"use client";

import { useEffect, useState } from "react";
import { getEtatFonctionnalites, type EtatFonctionnalites } from "./api";
import { fonctionnaliteDuChemin, modulesVisibles } from "./modules";

/**
 * Quelles parties de l'outil sont ouvertes à l'utilisateur courant.
 *
 * Le réglage est global et décidé par un administrateur depuis son écran. Un
 * administrateur reçoit tout ouvert : il doit pouvoir préparer et vérifier une
 * fonctionnalité que le public ne voit pas encore.
 *
 * Masquer une entrée ici ne protège rien — l'adresse reste tapable. Le
 * middleware du front et le garde du Core API font le vrai travail ; ceci
 * évite seulement de proposer une porte qui se refermera.
 *
 * Tant que la réponse n'est pas arrivée, tout est considéré comme ouvert.
 * C'est délibéré : afficher une navigation amputée pendant une seconde, puis
 * la voir se remplir, donne l'impression d'une application cassée. Le risque
 * inverse — une entrée visible brièvement alors qu'elle est fermée — se solde
 * par une redirection propre vers l'accueil.
 */
export function useFonctionnalites(): {
  ouverte: (cle: string) => boolean;
  etat: EtatFonctionnalites | null;
} {
  const [etat, setEtat] = useState<EtatFonctionnalites | null>(null);

  useEffect(() => {
    let vivant = true;
    getEtatFonctionnalites()
      .then((donnees) => {
        if (vivant) setEtat(donnees);
      })
      .catch(() => {
        // Injoignable : on laisse tout ouvert plutôt que d'amputer l'outil
        // sur un incident réseau.
        if (vivant) setEtat(null);
      });
    return () => {
      vivant = false;
    };
  }, []);

  return {
    etat,
    ouverte: (cle: string) => etat?.[cle] !== false,
  };
}

/**
 * La navigation complète : ce que le rôle autorise, et ce qu'un administrateur
 * a laissé ouvert.
 *
 * Les trois surfaces — menu, modules du tableau de bord, activités récentes —
 * passent par ici. Elles tenaient chacune leur règle, et la dernière en date
 * n'appliquait pas les fonctionnalités : un pan fermé disparaissait du menu
 * mais restait proposé sur le tableau de bord.
 */
export function useNavigation(role: string | undefined, surface: "accueil" | "menu") {
  const { ouverte, etat } = useFonctionnalites();
  return {
    etat,
    ouverte,
    modules: modulesVisibles(role, surface).filter((m) => ouverte(m.key)),
    /** Une adresse est-elle encore proposable ? */
    cheminOuvert: (href: string) => {
      const cle = fonctionnaliteDuChemin(href);
      return cle === null || ouverte(cle);
    },
  };
}
