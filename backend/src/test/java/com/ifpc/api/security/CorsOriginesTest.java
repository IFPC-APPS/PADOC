package com.ifpc.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Le réglage CORS ne doit jamais couper la production par omission.
 *
 * <p>Le 30/09/2026, {@code POST /api/auth/login} a répondu 403 à tous les
 * utilisateurs. La cause : une version de cette configuration retombait, à
 * défaut de {@code CORS_ALLOWED_ORIGINS}, sur les origines de développement —
 * et la variable n'était pas définie en production.</p>
 *
 * <p>Le raisonnement qui avait conduit à ce défaut était faux : on croyait le
 * CORS sans effet puisque le frontend appelle l'API en relatif et que Next
 * relaie côté serveur. Mais le relais retransmet l'en-tête {@code Origin} du
 * navigateur, que Spring évalue donc. Seuls les POST étaient touchés, les
 * navigateurs n'envoyant pas {@code Origin} sur un GET de même origine — ce
 * qui explique que les sondes de santé, toutes en GET, restaient vertes.</p>
 *
 * <p>Ces tests figent la leçon : la restriction est un choix explicite, jamais
 * la conséquence d'un oubli.</p>
 */
class CorsOriginesTest {

    private CorsConfiguration configurationPour(String valeurVariable) {
        SecurityConfiguration configuration = new SecurityConfiguration(null, null, null);
        ReflectionTestUtils.setField(configuration, "originesAutorisees", valeurVariable);
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) configuration.corsConfigurationSource();
        return source.getCorsConfigurations().get("/**");
    }

    @Test
    @DisplayName("sans variable, toutes les origines passent — un oubli ne coupe pas le service")
    void variableAbsenteNeCoupePas() {
        CorsConfiguration c = configurationPour("");

        assertEquals(java.util.List.of("*"), c.getAllowedOriginPatterns());
        assertNull(c.getAllowedOrigins(), "aucune liste stricte ne doit être posée");
    }

    @Test
    @DisplayName("une origine de production n'est pas refusée faute de configuration")
    void origineDeProductionAcceptee() {
        // Le cas exact de la panne : la variable n'est pas définie et le
        // navigateur envoie l'origine publique de l'application.
        CorsConfiguration c = configurationPour("");

        assertNotNull(c.checkOrigin("https://ifpc.vercel.app"),
                "c'est précisément ce refus qui a produit le 403 sur /api/auth/login");
    }

    @Test
    @DisplayName("renseignée, la variable restreint réellement")
    void variableRenseigneeRestreint() {
        CorsConfiguration c = configurationPour("https://ifpc.vercel.app, https://padoc.ifpc.eu");

        assertEquals(java.util.List.of("https://ifpc.vercel.app", "https://padoc.ifpc.eu"),
                c.getAllowedOrigins());
        assertNotNull(c.checkOrigin("https://ifpc.vercel.app"));
        assertNull(c.checkOrigin("https://site-tiers.example"),
                "une origine hors liste doit bien être refusée");
    }

    @Test
    @DisplayName("les espaces et entrées vides autour des virgules sont tolérés")
    void listeMalFormateeToleree() {
        CorsConfiguration c = configurationPour("  https://a.fr , , https://b.fr  ");

        assertEquals(java.util.List.of("https://a.fr", "https://b.fr"), c.getAllowedOrigins());
    }

    @Test
    @DisplayName("une liste vide de fait retombe sur le mode permissif")
    void listeDeVirgulesSeulesResteOuverte() {
        // « , , » ne dit pas « n'autoriser personne » : c'est une variable mal
        // remplie. La traiter comme une restriction couperait le service.
        CorsConfiguration c = configurationPour(" , , ");

        assertEquals(java.util.List.of("*"), c.getAllowedOriginPatterns());
    }

    @Test
    @DisplayName("les méthodes et en-têtes attendus par le frontend restent autorisés")
    void methodesEtEntetesConserves() {
        CorsConfiguration c = configurationPour("");

        assertTrue(c.getAllowedMethods().containsAll(
                java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS")));
        assertTrue(c.getAllowedHeaders().contains("Authorization"),
                "sans cet en-tête, aucune requête authentifiée ne passe");
        assertTrue(c.getExposedHeaders().contains("Authorization"));
    }
}
