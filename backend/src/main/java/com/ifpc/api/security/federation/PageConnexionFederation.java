package com.ifpc.api.security.federation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Le formulaire de connexion que voit l'utilisateur d'une plateforme fédérée.
 *
 * <p>Rendu en HTML depuis le contrôleur plutôt que par un moteur de gabarits :
 * l'application n'en embarque aucun, et en ajouter un pour une seule page
 * apporterait une dépendance, une configuration et une surface de plus. La
 * page est volontairement autonome — aucune ressource externe, donc rien qui
 * puisse manquer au moment précis où l'utilisateur s'authentifie.</p>
 *
 * <p>C'est la seule page d'IFPC que verront les utilisateurs venus d'un autre
 * outil : elle doit dire clairement chez qui ils sont.</p>
 */
@Controller
public class PageConnexionFederation {

    private final RequestCache demandesMemorisees = new HttpSessionRequestCache();

    /**
     * Où aller quand il n'y a rien à reprendre.
     *
     * <p>La racine de l'émetteur, c'est-à-dire l'application elle-même. « / »
     * désignerait la racine du serveur d'API, qui ne sert aucune page et
     * répond 403 — une impasse pour quelqu'un qui vient de se connecter.</p>
     */
    @Value("${federation.issuer:}")
    private String emetteur;

    private String repli() {
        return (emetteur == null || emetteur.isBlank()) ? "/" : emetteur.replaceAll("/+$", "") + "/";
    }

    private static org.springframework.http.HttpHeaders entetesVers(String adresse) {
        org.springframework.http.HttpHeaders entetes = new org.springframework.http.HttpHeaders();
        entetes.add(org.springframework.http.HttpHeaders.LOCATION, adresse);
        return entetes;
    }

    /**
     * Qui est déjà connecté n'a rien à faire ici.
     *
     * <p>Spring ne traite pas ce cas pour une page de connexion fournie par
     * l'application : il la sert telle quelle, même à une session authentifiée.
     * L'utilisateur voyait alors un formulaire qu'il venait de remplir avec
     * succès, sans message ni explication — et le remplissait de nouveau, avec
     * le même résultat.</p>
     *
     * <p>On reprend donc la demande d'autorisation mémorisée, ce qui est la
     * suite naturelle du parcours. À défaut, on renvoie à la racine de
     * l'émetteur plutôt que de réafficher le formulaire.</p>
     */
    private String reprendreLeParcours(HttpServletRequest requete, HttpServletResponse reponse) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean connecte = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
        if (!connecte) {
            return null;
        }

        SavedRequest memorisee = demandesMemorisees.getRequest(requete, reponse);
        // CONSOMMÉE, et pas seulement lue. Sans ce retrait, chaque passage sur
        // cette page renvoyait vers la même demande ; si celle-ci ramenait ici
        // pour une raison quelconque — demande périmée, code déjà délivré — le
        // navigateur rebondissait indéfiniment entre les deux, et rien ne
        // cassait jamais le cycle. ERR_TOO_MANY_REDIRECTS.
        demandesMemorisees.removeRequest(requete, reponse);

        String suite = memorisee != null ? memorisee.getRedirectUrl() : null;
        // Et jamais vers cette page : une demande mémorisée qui pointerait ici
        // formerait une boucle à elle seule.
        if (suite == null || suite.contains(ConfigurationFederation.CHEMIN_CONNEXION)) {
            return repli();
        }
        return suite;
    }

    @GetMapping(value = ConfigurationFederation.CHEMIN_CONNEXION, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public Object afficher(
            CsrfToken jetonCsrf,
            HttpServletRequest requete,
            HttpServletResponse reponse,
            @RequestParam(name = "erreur", required = false) String erreur,
            @RequestParam(name = "deconnecte", required = false) String deconnecte) {

        // « deconnecte » excepté : on vient précisément de fermer la session,
        // et la reprise n'aurait aucun sens.
        if (deconnecte == null) {
            String suite = reprendreLeParcours(requete, reponse);
            if (suite != null) {
                return new org.springframework.http.ResponseEntity<Void>(
                        entetesVers(suite), org.springframework.http.HttpStatus.FOUND);
            }
        }

        String banniere = "";
        if (erreur != null) {
            // Le motif exact n'est pas dit : distinguer « mot de passe faux »
            // de « compte inconnu » permettrait d'énumérer les adresses qui
            // ont un compte. Le cas « en attente de validation » est en
            // revanche annoncé plus loin dans le parcours, une fois la
            // personne authentifiée (ControleAccesPlateforme).
            banniere = """
                    <p class="alerte">Identifiants invalides, ou compte non encore validé.</p>""";
        } else if (deconnecte != null) {
            banniere = """
                    <p class="info">Vous êtes déconnecté.</p>""";
        }

        return """
                <!doctype html>
                <html lang="fr"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Connexion IFPC</title>
                <style>
                  *{box-sizing:border-box}
                  body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
                       background:#f6f7f5;color:#1f2420;display:flex;min-height:100vh;
                       align-items:center;justify-content:center;margin:0;padding:1.5rem}
                  main{background:#fff;border:1px solid #e2e5df;border-radius:12px;
                       padding:2rem;width:100%%;max-width:23rem}
                  h1{font-size:1.15rem;margin:0 0 .25rem}
                  .sous{margin:0 0 1.5rem;color:#6b7269;font-size:.875rem;line-height:1.5}
                  label{display:block;font-size:.8125rem;font-weight:600;margin:0 0 .375rem}
                  input{width:100%%;padding:.625rem .75rem;margin-bottom:1rem;
                        border:1px solid #d5d9d1;border-radius:8px;font-size:.9375rem;
                        font-family:inherit;background:#fff;color:inherit}
                  input:focus{outline:2px solid #3d7a4a;outline-offset:-1px;border-color:transparent}
                  button{width:100%%;padding:.6875rem;border:0;border-radius:8px;
                         background:#2f6b3c;color:#fff;font-size:.9375rem;font-weight:600;
                         font-family:inherit;cursor:pointer}
                  button:hover{background:#265831}
                  .alerte,.info{font-size:.8125rem;padding:.625rem .75rem;border-radius:8px;
                                margin:0 0 1rem;line-height:1.45}
                  .alerte{background:#fdf0ee;color:#8f2d1c;border:1px solid #f4d5ce}
                  .info{background:#eef4ef;color:#2f5c39;border:1px solid #cfe2d4}
                  .pied{margin:1.25rem 0 0;font-size:.75rem;color:#8b928a;text-align:center}
                  .pied a{color:#8b928a}
                </style></head>
                <body><main>
                  <h1>Connexion IFPC</h1>
                  <p class="sous">Vous vous connectez avec votre compte IFPC pour accéder
                     à une plateforme partenaire.</p>
                  %s
                  <form method="post" action="%s">
                    <label for="username">Adresse e-mail</label>
                    <input id="username" name="username" type="email"
                           autocomplete="username" required autofocus>
                    <label for="password">Mot de passe</label>
                    <input id="password" name="password" type="password"
                           autocomplete="current-password" required>
                    <input type="hidden" name="%s" value="%s">
                    <button type="submit">Se connecter</button>
                  </form>
                  <p class="pied"><a href="%s">Comment fonctionne cette connexion&nbsp;?</a></p>
                </main></body></html>
                """.formatted(
                banniere,
                ConfigurationFederation.CHEMIN_CONNEXION,
                jetonCsrf.getParameterName(),
                jetonCsrf.getToken(),
                DocumentationFederation.CHEMIN);
    }
}
