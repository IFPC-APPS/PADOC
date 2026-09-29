package com.ifpc.api.security.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Le guide d'intégration doit donner les vraies adresses de l'instance.
 *
 * <p>Une documentation dont les exemples sont faux est pire qu'absente : le
 * lecteur les recopie. D'où ces vérifications, qui portent moins sur la mise en
 * page que sur l'exactitude des URL et la présence des mises en garde qu'un
 * intégrateur ne doit pas manquer.</p>
 */
class DocumentationFederationTest {

    private static final String EMETTEUR = "https://padoc.ifpc.eu";

    private String page(String emetteur) {
        AuthorizationServerSettings.Builder reglages = AuthorizationServerSettings.builder();
        if (emetteur != null) {
            reglages.issuer(emetteur);
        }
        MockHttpServletRequest requete = new MockHttpServletRequest("GET", DocumentationFederation.CHEMIN);
        requete.setScheme("https");
        requete.setServerName("replis.exemple.fr");
        requete.setServerPort(443);
        requete.setRequestURI(DocumentationFederation.CHEMIN);
        return new DocumentationFederation(reglages.build()).afficher(requete);
    }

    @Test
    @DisplayName("les URL affichées sont celles de l'émetteur configuré")
    void urlsDeLEmetteurConfigure() {
        String html = page(EMETTEUR);

        assertTrue(html.contains(EMETTEUR + "/.well-known/openid-configuration"));
        assertTrue(html.contains(EMETTEUR + "/oauth2/authorize"));
        assertTrue(html.contains(EMETTEUR + "/oauth2/token"));
        assertTrue(html.contains(EMETTEUR + "/oauth2/jwks"));
        assertTrue(html.contains(EMETTEUR + "/userinfo"));
        assertTrue(html.contains(EMETTEUR + "/oauth2/revoke"));
    }

    @Test
    @DisplayName("aucun gabarit non substitué ne subsiste")
    void aucunGabaritOublie() {
        assertFalse(page(EMETTEUR).contains("{{"),
                "un marqueur non remplacé afficherait « {{jwks}} » au lecteur");
    }

    @Test
    @DisplayName("sans émetteur configuré, la page retombe sur l'adresse de la requête")
    void repliSurLaRequete() {
        String html = page(null);

        assertTrue(html.contains("https://replis.exemple.fr/.well-known/openid-configuration"));
        assertFalse(html.contains("{{"));
    }

    @Test
    @DisplayName("une barre oblique finale sur l'émetteur ne produit pas d'URL doublée")
    void barreObliqueFinaleAbsorbee() {
        String html = page(EMETTEUR + "/");

        assertTrue(html.contains(EMETTEUR + "/oauth2/token"));
        assertFalse(html.contains("//oauth2/token"), "une URL en « //oauth2 » serait injoignable");
    }

    @Test
    @DisplayName("les mises en garde qu'un intégrateur ne doit pas manquer sont présentes")
    void misesEnGardePresentes() {
        String html = page(EMETTEUR);

        // Chacune correspond à un mode d'échec réel côté plateforme cliente.
        assertTrue(html.contains("organisation"), "le claim organisation est déclaratif");
        assertTrue(html.contains("frontière d'autorisation"),
                "sans cet avertissement, une plateforme cloisonne sur un champ que "
                        + "l'utilisateur modifie lui-même");
        assertTrue(html.contains("prise de contrôle de compte"),
                "le rattachement par e-mail non vérifié doit être explicitement écarté");
        assertTrue(html.contains("refresh_token"), "l'absence pour un client public doit être dite");
        assertTrue(html.contains("kid"), "le rafraîchissement du cache JWKS conditionne la rotation");
        assertTrue(html.contains("Accès non accordé"),
                "l'intégrateur doit savoir que ce refus n'est pas un défaut de son code");
    }

    @Test
    @DisplayName("la page ne charge aucune ressource externe et ne révèle aucun client")
    void pageAutonome() {
        String html = page(EMETTEUR);

        assertFalse(html.contains("<script"), "aucun script : la page doit rester inerte");
        assertFalse(html.contains("http://") && html.contains("cdn"),
                "aucune ressource externe : la page doit s'afficher derrière un réseau filtré");
        assertTrue(html.startsWith("<!doctype html>"));
    }
}
