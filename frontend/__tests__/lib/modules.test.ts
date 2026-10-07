import { MODULES, cheminsAdministrateur, fonctionnaliteDuChemin, modulesVisibles } from "@/lib/modules";

/**
 * La page d'accueil et la barre latérale lisent la même liste. Ces tests
 * figent ce qui, auparavant, dépendait de la vigilance de qui modifiait l'une
 * des deux : un retrait fait d'un côté et oublié de l'autre laissait des
 * entrées proposées à des comptes qui ne pouvaient pas les ouvrir.
 */
describe("visibilité des modules", () => {
  const destinations = (role: string | undefined, surface: "accueil" | "menu") =>
    modulesVisibles(role, surface).flatMap((m) => m.sousModules.map((s) => s.href));

  it("un non-administrateur ne voit aucune destination réservée, nulle part", () => {
    const reservees = cheminsAdministrateur();
    expect(reservees.length).toBeGreaterThan(0);

    for (const surface of ["accueil", "menu"] as const) {
      for (const role of [undefined, "USER", "EXPERT", "PENDING"]) {
        for (const chemin of destinations(role, surface)) {
          expect(reservees).not.toContain(chemin);
        }
      }
    }
  });

  it("l'accueil et le menu s'accordent pour un même compte", () => {
    // C'est l'écart exact qui avait été signalé : la page principale masquait,
    // le menu non.
    for (const role of [undefined, "USER", "EXPERT", "ADMIN"]) {
      const accueil = new Set(destinations(role, "accueil"));
      for (const chemin of destinations(role, "menu")) {
        expect(accueil.has(chemin)).toBe(true);
      }
    }
  });

  it("un administrateur accède bien aux destinations réservées", () => {
    const accueil = destinations("ADMIN", "accueil");
    for (const chemin of cheminsAdministrateur()) {
      expect(accueil).toContain(chemin);
    }
  });

  it("l'administration n'apparaît pas deux fois dans le menu", () => {
    // La barre latérale propose déjà « Admin » en bas : l'ajouter comme module
    // ferait doublon.
    expect(destinations("ADMIN", "menu")).not.toContain("/admin");
  });
});

describe("déclaration des modules", () => {
  it("chaque module a une clé unique et au moins une destination", () => {
    const cles = MODULES.map((m) => m.key);
    expect(new Set(cles).size).toBe(cles.length);
    for (const m of MODULES) {
      expect(m.sousModules.length).toBeGreaterThan(0);
      expect(m.labelKeyAccueil.length).toBeGreaterThan(0);
      expect(m.labelKeyMenu.length).toBeGreaterThan(0);
    }
  });

  it("les clés de module servent aussi de clés de fonctionnalité", () => {
    // Le garde du Core API et le middleware du front désignent les mêmes pans
    // par ces noms : les désaccorder fermerait ou ouvrirait le mauvais écran.
    for (const cle of ["pasteurisation", "colorimetrie", "cuves"]) {
      expect(MODULES.map((m) => m.key)).toContain(cle);
    }
  });

  it("aucune destination n'est déclarée deux fois", () => {
    const tous = MODULES.flatMap((m) => m.sousModules.map((s) => s.href));
    expect(new Set(tous).size).toBe(tous.length);
  });
});

describe("fonctionnalité d'une adresse", () => {
  it("rattache les destinations des modules", () => {
    expect(fonctionnaliteDuChemin("/controle")).toBe("pasteurisation");
    expect(fonctionnaliteDuChemin("/bareme")).toBe("pasteurisation");
    expect(fonctionnaliteDuChemin("/colorimetrie/assemblage")).toBe("colorimetrie");
    expect(fonctionnaliteDuChemin("/cuves/chai")).toBe("cuves");
  });

  it("rattache aussi ce qui n'a pas de module sur l'accueil", () => {
    // La section « Reprendre » pointe droit vers ces écrans : sans eux, fermer
    // l'historique le retirait du menu mais le laissait atteignable d'un clic.
    expect(fonctionnaliteDuChemin("/historique")).toBe("historique");
    expect(fonctionnaliteDuChemin("/assistant")).toBe("assistant");
  });

  it("ne capture pas une adresse au nom voisin", () => {
    expect(fonctionnaliteDuChemin("/cuvesphere")).toBeNull();
    expect(fonctionnaliteDuChemin("/historiquement")).toBeNull();
  });

  it("laisse passer ce qui ne dépend d'aucune fonctionnalité", () => {
    // Fermer l'administration ou le profil enfermerait tout le monde dehors.
    for (const chemin of ["/", "/profil", "/login", "/admin", "/expert"]) {
      expect(fonctionnaliteDuChemin(chemin)).toBeNull();
    }
  });
});
