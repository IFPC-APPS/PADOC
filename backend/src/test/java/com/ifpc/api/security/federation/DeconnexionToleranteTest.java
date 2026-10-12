package com.ifpc.api.security.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Une déconnexion refusée par le serveur d'autorisation ferme quand même la
 * session, et ne renvoie jamais vers une adresse fournie par la requête.
 */
class DeconnexionToleranteTest {

    private final RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
    private final DeconnexionTolerante gestionnaire =
            new DeconnexionTolerante(clients, "https://padoc.ifpc.eu/federation/connexion");

    private static final OAuth2AuthenticationException REFUS =
            new OAuth2AuthenticationException(new OAuth2Error("invalid_request", "post_logout_redirect_uri", null));

    @Test
    @DisplayName("la session est fermée et l'on revient sur l'adresse enregistrée du client")
    void sessionFermeeRetourEnregistre() throws Exception {
        when(clients.findByClientId("ciderscope")).thenReturn(RegisteredClient.withId("x")
                .clientId("ciderscope")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://cs.exemple.fr/api/auth/ifpc/callback")
                .postLogoutRedirectUri("https://cs.exemple.fr/")
                .build());

        MockHttpServletRequest requete = new MockHttpServletRequest("GET", "/connect/logout");
        MockHttpSession session = new MockHttpSession();
        requete.setSession(session);
        requete.setParameter("client_id", "ciderscope");
        requete.setParameter("post_logout_redirect_uri", "https://attaquant.exemple/");
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        gestionnaire.onAuthenticationFailure(requete, reponse, REFUS);

        assertTrue(session.isInvalid(), "vouloir se déconnecter suffit à fermer la session");
        assertEquals("https://cs.exemple.fr/", reponse.getRedirectedUrl(),
                "jamais l'adresse de la requête : c'est elle qui a pu être refusée");
    }

    @Test
    @DisplayName("client inconnu : retour au formulaire de connexion de PADOC")
    void clientInconnu() throws Exception {
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        gestionnaire.onAuthenticationFailure(new MockHttpServletRequest(), reponse, REFUS);

        assertEquals("https://padoc.ifpc.eu/federation/connexion?deconnecte", reponse.getRedirectedUrl());
    }
}
