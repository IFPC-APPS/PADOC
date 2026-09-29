package com.ifpc.api.security.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * L'enregistrement de la plateforme cliente.
 *
 * <p>Le cas le plus important est celui où rien n'est configuré : la fédération
 * doit alors rester inerte. Un client créé par défaut serait un accès public
 * livré avec le dépôt.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AmorceClientsTest {

    @Mock private RegisteredClientRepository clients;
    @Mock private PasswordEncoder encodeur;

    private AmorceClients amorce(String id, String secret, String redirections) {
        AmorceClients a = new AmorceClients(clients, encodeur);
        ReflectionTestUtils.setField(a, "clientId", id);
        ReflectionTestUtils.setField(a, "clientSecret", secret);
        ReflectionTestUtils.setField(a, "clientNom", "Analyse sensorielle");
        ReflectionTestUtils.setField(a, "redirectUris", redirections);
        ReflectionTestUtils.setField(a, "postLogoutUris", "");
        return a;
    }

    private RegisteredClient enregistre() {
        ArgumentCaptor<RegisteredClient> capture = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(clients).save(capture.capture());
        return capture.getValue();
    }

    @Test
    @DisplayName("sans client_id configuré, aucun client n'est enregistré")
    void sansConfigurationRienNEstCree() {
        amorce("", "", "https://exemple.fr/cb").amorcer();
        verify(clients, never()).save(any());
    }

    @Test
    @DisplayName("sans URI de redirection, le client est refusé — il n'y aurait pas de parcours")
    void sansRedirectionRefuse() {
        amorce("analyse", "", "").amorcer();
        verify(clients, never()).save(any());
    }

    @Test
    @DisplayName("un client déjà enregistré n'est pas réécrit")
    void clientExistantPreserve() {
        when(clients.findByClientId("analyse")).thenReturn(
                RegisteredClient.withId("x").clientId("analyse")
                        .authorizationGrantType(
                                org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://exemple.fr/cb")
                        .build());

        amorce("analyse", "", "https://exemple.fr/cb").amorcer();

        verify(clients, never()).save(any());
    }

    @Test
    @DisplayName("sans secret, le client est public : aucune authentification de client exigée")
    void clientPublicSansSecret() {
        amorce("analyse", "", "https://exemple.fr/cb").amorcer();

        RegisteredClient client = enregistre();
        assertTrue(client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE));
        assertNull(client.getClientSecret());
        verify(encodeur, never()).encode(anyString());
    }

    @Test
    @DisplayName("avec secret, le client est confidentiel et le secret est haché")
    void clientConfidentielAvecSecret() {
        when(encodeur.encode("tres-secret")).thenReturn("{bcrypt}hache");

        amorce("analyse", "tres-secret", "https://exemple.fr/cb").amorcer();

        RegisteredClient client = enregistre();
        assertTrue(client.getClientAuthenticationMethods()
                .contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC));
        assertEquals("{bcrypt}hache", client.getClientSecret(),
                "un secret stocké en clair serait lisible par qui lit la base");
    }

    @Test
    @DisplayName("PKCE et consentement sont exigés, y compris d'un client confidentiel")
    void pkceEtConsentementExiges() {
        when(encodeur.encode(anyString())).thenReturn("hache");

        amorce("analyse", "tres-secret", "https://exemple.fr/cb").amorcer();

        RegisteredClient client = enregistre();
        assertTrue(client.getClientSettings().isRequireProofKey(),
                "PKCE lie le code à la session qui l'a demandé : un code intercepté reste inutilisable");
        assertTrue(client.getClientSettings().isRequireAuthorizationConsent());
    }

    @Test
    @DisplayName("les URI de redirection sont reprises exactement, sans joker")
    void redirectionsExactes() {
        amorce("analyse", "", "https://a.exemple.fr/cb, https://b.exemple.fr/cb").amorcer();

        RegisteredClient client = enregistre();
        assertEquals(2, client.getRedirectUris().size());
        assertTrue(client.getRedirectUris().contains("https://a.exemple.fr/cb"));
        assertTrue(client.getRedirectUris().contains("https://b.exemple.fr/cb"));
        assertTrue(client.getRedirectUris().stream().noneMatch(u -> u.contains("*")));
    }

    @Test
    @DisplayName("les durées de vie sont celles de la spécification, avec rotation du rafraîchissement")
    void dureesDeVieEtRotation() {
        amorce("analyse", "", "https://exemple.fr/cb").amorcer();

        var jetons = enregistre().getTokenSettings();
        assertEquals(10, jetons.getAccessTokenTimeToLive().toMinutes(),
                "un jeton d'accès court est ce qui rend la reprise de contrôle possible");
        assertEquals(14, jetons.getRefreshTokenTimeToLive().toDays());
        assertEquals(1, jetons.getAuthorizationCodeTimeToLive().toMinutes());
        assertFalse(jetons.isReuseRefreshTokens(),
                "sans rotation, un jeton de rafraîchissement volé sert indéfiniment");
    }

    @Test
    @DisplayName("les portées OIDC de base sont accordées")
    void porteesOidc() {
        amorce("analyse", "", "https://exemple.fr/cb").amorcer();

        var portees = enregistre().getScopes();
        assertTrue(portees.containsAll(java.util.Set.of("openid", "profile", "email")));
    }
}
