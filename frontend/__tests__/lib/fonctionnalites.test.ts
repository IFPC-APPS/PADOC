import { CHEMINS_FEDERATION, fonctionnaliteDe } from "@/lib/fonctionnalites-chemins";

/**
 * Deux règles du middleware qui, cassées, produisent des pannes muettes :
 * un parcours de fédération renvoyé vers l'écran de connexion habituel, et
 * une page fermée par erreur parce qu'un chemin voisin lui ressemblait.
 */
describe("rattachement d'une page à sa fonctionnalité", () => {
  it("rattache les pages exactes", () => {
    expect(fonctionnaliteDe("/controle")).toBe("pasteurisation");
    expect(fonctionnaliteDe("/bareme")).toBe("pasteurisation");
    expect(fonctionnaliteDe("/assistant")).toBe("assistant");
    expect(fonctionnaliteDe("/historique")).toBe("historique");
  });

  it("rattache les sous-pages", () => {
    expect(fonctionnaliteDe("/colorimetrie/assemblage")).toBe("colorimetrie");
    expect(fonctionnaliteDe("/cuves/chai")).toBe("cuves");
  });

  it("arrête aussi les appels à l'assistant, servi par un autre service", () => {
    // Ces appels ne traversent pas le Core API : son garde ne les voit jamais.
    // Si le middleware les laissait passer, fermer l'assistant n'aurait aucun
    // effet sur ses requêtes.
    expect(fonctionnaliteDe("/api/ldc/ask")).toBe("assistant");
  });

  it("ne capture pas un chemin au nom voisin", () => {
    expect(fonctionnaliteDe("/controleur")).toBeNull();
    expect(fonctionnaliteDe("/cuvesphere")).toBeNull();
    expect(fonctionnaliteDe("/historiquement")).toBeNull();
  });

  it("laisse passer ce qui n'est rattaché à rien", () => {
    expect(fonctionnaliteDe("/")).toBeNull();
    expect(fonctionnaliteDe("/login")).toBeNull();
    expect(fonctionnaliteDe("/admin")).toBeNull();
    expect(fonctionnaliteDe("/profil")).toBeNull();
    expect(fonctionnaliteDe("/api/auth/login")).toBeNull();
  });

  it("ne ferme jamais l'écran d'administration", () => {
    // C'est de là qu'on rouvre une fonctionnalité : le fermer rendrait la
    // décision irréversible depuis l'interface.
    expect(fonctionnaliteDe("/admin")).toBeNull();
    expect(fonctionnaliteDe("/api/admin/fonctionnalites")).toBeNull();
  });
});

describe("chemins de la fédération", () => {
  it("couvre tout le serveur d'autorisation", () => {
    // Sans ces entrées, le middleware renvoie /federation/connexion vers
    // /login : le parcours fédéré s'interrompt au premier saut, et l'utilisateur
    // voit l'écran de connexion habituel sans comprendre pourquoi.
    for (const chemin of ["/federation", "/oauth2", "/.well-known", "/userinfo", "/connect"]) {
      expect(CHEMINS_FEDERATION).toContain(chemin);
    }
  });

  it("aucun chemin de fédération n'est rattaché à une fonctionnalité", () => {
    // Un administrateur ne doit pas pouvoir fermer la connexion fédérée en
    // fermant un pan de l'application.
    for (const chemin of CHEMINS_FEDERATION) {
      expect(fonctionnaliteDe(chemin)).toBeNull();
    }
  });
});
