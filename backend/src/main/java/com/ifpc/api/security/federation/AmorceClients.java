package com.ifpc.api.security.federation;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Déclare la plateforme cliente à partir de la configuration d'environnement.
 *
 * <p>Un client n'est pas créé automatiquement : sans {@code FEDERATION_CLIENT_ID}
 * et au moins une URI de redirection, rien n'est enregistré et la fédération
 * reste en sommeil — les points d'entrée répondent, mais personne n'est
 * autorisé à s'en servir. C'est voulu : un client par défaut dans le dépôt
 * serait un accès public.</p>
 */
@Component
@RequiredArgsConstructor
public class AmorceClients {

    private static final Logger log = LoggerFactory.getLogger(AmorceClients.class);

    /**
     * Durée de vie du jeton d'accès.
     *
     * <p>Dix minutes, contre 24 h pour le jeton de l'API PADOC. Un jeton court
     * est ce qui rend la reprise de contrôle possible : désactiver un compte ou
     * retirer une habilitation prend effet au prochain rafraîchissement, au lieu
     * d'attendre l'expiration (spec §10).</p>
     */
    private static final Duration VIE_JETON_ACCES = Duration.ofMinutes(10);
    private static final Duration VIE_JETON_RAFRAICHISSEMENT = Duration.ofDays(14);
    /** Un code d'autorisation transite par le navigateur : il ne doit pas traîner. */
    private static final Duration VIE_CODE = Duration.ofMinutes(1);

    private final RegisteredClientRepository clients;
    private final PasswordEncoder encodeurMotDePasse;

    @Value("${federation.client.id:}")
    private String clientId;

    @Value("${federation.client.secret:}")
    private String clientSecret;

    @Value("${federation.client.nom:Plateforme partenaire}")
    private String clientNom;

    @Value("${federation.client.redirect-uris:}")
    private String redirectUris;

    @Value("${federation.client.post-logout-uris:}")
    private String postLogoutUris;

    public void amorcer() {
        if (clientId == null || clientId.isBlank()) {
            log.info("Fédération : aucun client déclaré (FEDERATION_CLIENT_ID vide). "
                    + "Les points d'entrée sont actifs, aucune plateforme n'est autorisée.");
            return;
        }
        List<String> redirections = decouper(redirectUris);
        if (redirections.isEmpty()) {
            log.error("Fédération : le client « {} » n'a aucune URI de redirection "
                    + "(FEDERATION_CLIENT_REDIRECT_URIS). Il n'est pas enregistré : sans URI "
                    + "exacte, il n'y a pas de parcours possible.", clientId);
            return;
        }
        if (clients.findByClientId(clientId) != null) {
            return;
        }

        boolean confidentiel = clientSecret != null && !clientSecret.isBlank();

        RegisteredClient.Builder client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName(clientNom)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .clientSettings(ClientSettings.builder()
                        // PKCE exigé même d'un client confidentiel : il lie le
                        // code à la session qui l'a demandé, donc un code
                        // intercepté dans un journal, un historique ou un
                        // en-tête Referer est inutilisable (spec §6.3).
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(VIE_JETON_ACCES)
                        .refreshTokenTimeToLive(VIE_JETON_RAFRAICHISSEMENT)
                        .authorizationCodeTimeToLive(VIE_CODE)
                        // Rotation : chaque usage émet un nouveau jeton de
                        // rafraîchissement et invalide le précédent. Un jeton
                        // rejoué signale un vol (spec §10).
                        .reuseRefreshTokens(false)
                        .build());

        // Correspondance exacte, jamais de joker : un motif trop large permet de
        // détourner la redirection vers un domaine tenu par un attaquant, avec
        // un code d'autorisation valide (spec §7.3).
        redirections.forEach(client::redirectUri);
        decouper(postLogoutUris).forEach(client::postLogoutRedirectUri);

        if (confidentiel) {
            client.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientSecret(encodeurMotDePasse.encode(clientSecret));
        } else {
            // Client public : une application de navigateur ne peut pas garder
            // un secret. Aucun n'est donc exigé, et la sécurité repose sur PKCE
            // et la correspondance exacte des URI de redirection.
            client.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }

        clients.save(client.build());
        log.info("Fédération : client « {} » enregistré ({}), {} URI de redirection.",
                clientId, confidentiel ? "confidentiel" : "public", redirections.size());
    }

    private static List<String> decouper(String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return List.of();
        }
        return Arrays.stream(valeur.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .toList();
    }
}
