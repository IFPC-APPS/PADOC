/**
 * Quel chemin dépend de quelle fonctionnalité.
 *
 * Module séparé du middleware, et sans dépendance à `next/server` : ces règles
 * sont de la logique pure, et elles doivent pouvoir être testées sans monter
 * l'environnement d'exécution de Next.
 */

/**
 * Chemins servis par le Core API et relayés par next.config.mjs : le serveur
 * d'autorisation de la fédération, et le guide d'intégration destiné aux
 * plateformes partenaires.
 *
 * Sans eux, le middleware renvoie vers /login tout ce qui ne commence pas par
 * /api et ne contient pas de point — donc /federation/connexion, le formulaire
 * même de la fédération. Le parcours s'interromprait au premier saut, et la
 * cause serait invisible : le navigateur afficherait simplement l'écran de
 * connexion habituel au lieu du formulaire fédéré.
 *
 * Sur la VM le défaut ne se voit pas, un proxy interceptant ces chemins avant
 * Next. En production il n'y a pas de proxy : c'est là que tout se joue.
 */
export const CHEMINS_FEDERATION = [
  "/federation",
  "/oauth2",
  "/.well-known",
  "/userinfo",
  "/connect",
];

/**
 * Page ou route → la fonctionnalité dont elle dépend.
 *
 * Les clés sont celles du catalogue du Core API (ServiceFonctionnalites).
 * Le préfixe le plus long l'emporte, pour qu'une page puisse être rattachée
 * plus finement qu'un groupe entier.
 */
export const FONCTIONNALITE_PAR_CHEMIN: Record<string, string> = {
  "/controle": "pasteurisation",
  "/bareme": "pasteurisation",
  "/colorimetrie": "colorimetrie",
  "/cuves": "cuves",
  "/lots": "cuves",
  "/assistant": "assistant",
  "/historique": "historique",
  // L'assistant est servi par un service séparé : ses appels ne traversent pas
  // le Core API, donc son garde ne les voit jamais. Ils sont arrêtés ici.
  "/api/ldc": "assistant",
};

export function estCheminFederation(pathname: string): boolean {
  return CHEMINS_FEDERATION.some((p) => pathname === p || pathname.startsWith(p + "/"));
}

export function fonctionnaliteDe(pathname: string): string | null {
  let trouvee: string | null = null;
  let plusLong = "";
  for (const [prefixe, cle] of Object.entries(FONCTIONNALITE_PAR_CHEMIN)) {
    // Fin du chemin ou séparateur : « /cuves » ne doit pas emporter
    // « /cuvesphere », qui n'a rien à voir et cesserait de répondre sans que
    // le lien avec la fonctionnalité fermée soit devinable.
    const correspond = pathname === prefixe || pathname.startsWith(prefixe + "/");
    if (correspond && prefixe.length > plusLong.length) {
      plusLong = prefixe;
      trouvee = cle;
    }
  }
  return trouvee;
}
