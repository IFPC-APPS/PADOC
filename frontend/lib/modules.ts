/**
 * Les modules de PADOC, décrits une seule fois.
 *
 * La page d'accueil et la barre latérale en tenaient chacune sa propre liste.
 * Elles ont divergé : un retrait fait d'un côté n'avait pas été reporté de
 * l'autre, et des entrées restaient proposées dans le menu à des comptes qui
 * ne pouvaient pas les ouvrir. Deux listes des mêmes choses finissent toujours
 * par se désaccorder — ce n'est pas une question de vigilance.
 *
 * Ce fichier ne décrit que la STRUCTURE : clés, intitulés, destinations,
 * restrictions. Chaque surface garde sa propre mise en forme — l'accueil ses
 * glyphes métier, la barre latérale ses icônes — parce que c'est là que les
 * deux diffèrent légitimement.
 */

export interface SousModule {
  href: string;
  /** Clé de traduction, résolue par chaque surface. */
  labelKey: string;
}

export interface ModuleDeclare {
  /** Identifiant stable, aussi utilisé comme clé de fonctionnalité. */
  key: string;
  labelKeyAccueil: string;
  labelKeyMenu: string;
  /** Réservé aux comptes administrateurs. */
  adminOnly?: boolean;
  /** Absent de la barre latérale : l'accueil seul le propose. */
  accueilSeulement?: boolean;
  sousModules: SousModule[];
}

export const MODULES: ModuleDeclare[] = [
  {
    key: "pasteurisation",
    labelKeyAccueil: "home.modules.pasteurisation",
    labelKeyMenu: "nav.pasteurisation",
    sousModules: [
      { href: "/controle", labelKey: "nav.calculVP" },
      { href: "/bareme", labelKey: "nav.aideBareme" },
    ],
  },
  {
    key: "colorimetrie",
    labelKeyAccueil: "home.modules.colorimetrie",
    labelKeyMenu: "nav.colorimetrie",
    sousModules: [
      { href: "/colorimetrie/assemblage", labelKey: "colori.title" },
    ],
  },
  {
    key: "cuves",
    labelKeyAccueil: "home.cards.cuvesTitre",
    labelKeyMenu: "nav.gestionCuves",
    sousModules: [
      // Suivi des cuves, Lots / Produits et Corbeille ont été retirés : les
      // pages existent toujours et restent atteignables par leur adresse, mais
      // elles ne sont plus proposées. Le retrait vaut pour les deux surfaces,
      // puisqu'elles lisent la même liste.
      { href: "/cuves/chai", labelKey: "nav.chaiVirtuel" },
    ],
  },
  {
    key: "administration",
    labelKeyAccueil: "home.modules.admin",
    labelKeyMenu: "home.modules.admin",
    adminOnly: true,
    // La barre latérale propose déjà « Admin » séparément, en bas ; l'ajouter
    // ici ferait doublon.
    accueilSeulement: true,
    sousModules: [
      { href: "/admin", labelKey: "home.modules.users" },
      { href: "/expert", labelKey: "home.modules.config" },
    ],
  },
];

/**
 * Les modules qu'un compte a le droit de voir.
 *
 * Une seule règle, appliquée partout : si un module est réservé aux
 * administrateurs, il disparaît des deux surfaces à la fois. C'est
 * précisément ce qui manquait.
 */
export const modulesVisibles = (
  role: string | undefined,
  surface: "accueil" | "menu",
): ModuleDeclare[] =>
  MODULES.filter((m) => {
    if (m.adminOnly && role !== "ADMIN") return false;
    if (m.accueilSeulement && surface === "menu") return false;
    return true;
  });

/** Toutes les destinations réservées aux administrateurs. */
export const cheminsAdministrateur = (): string[] =>
  MODULES.filter((m) => m.adminOnly).flatMap((m) => m.sousModules.map((s) => s.href));

/**
 * À quelle fonctionnalité appartient une adresse.
 *
 * Le tableau de bord ne propose pas que des modules : sa section « Reprendre »
 * pointe directement vers des écrans. Sans cette correspondance, fermer un pan
 * le retirait du menu et des modules, mais le laissait atteignable d'un clic
 * depuis la liste des activités récentes.
 *
 * Les clés hors catalogue — « /historique », « /assistant » — sont déclarées
 * ici aussi : ce sont des fonctionnalités à part entière côté Core API, même
 * si elles n'ont pas de module sur l'accueil.
 */
const CHEMINS_HORS_MODULES: Record<string, string> = {
  "/historique": "historique",
  "/assistant": "assistant",
};

export const fonctionnaliteDuChemin = (href: string): string | null => {
  let trouvee: string | null = null;
  let plusLong = "";
  const candidats: [string, string][] = [
    ...Object.entries(CHEMINS_HORS_MODULES),
    // Les modules réservés aux administrateurs sont écartés : leur accès tient
    // au rôle, pas à une fonctionnalité. Les y soumettre permettrait de fermer
    // l'écran d'administration — c'est-à-dire l'endroit même d'où l'on
    // rouvrirait ce qu'on vient de fermer.
    ...MODULES.filter((m) => !m.adminOnly)
      .flatMap((m) => m.sousModules.map((s) => [s.href, m.key] as [string, string])),
  ];
  for (const [chemin, cle] of candidats) {
    // Fin de chemin ou séparateur : « /cuves » ne doit pas emporter
    // « /cuvesphere », qui n'a rien à voir.
    const correspond = href === chemin || href.startsWith(chemin + "/");
    if (correspond && chemin.length > plusLong.length) {
      plusLong = chemin;
      trouvee = cle;
    }
  }
  return trouvee;
};
