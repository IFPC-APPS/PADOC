package com.ifpc.api.security.federation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

import java.io.IOException;

/**
 * Déconnexion demandée par une plateforme, même quand la demande est refusée.
 *
 * <p>Le serveur d'autorisation rejette la demande en 400 dès qu'un détail ne
 * va pas — adresse de retour non enregistrée, {@code id_token_hint} inconnu,
 * session différente de celle du jeton — et l'utilisateur tombait sur une page
 * d'erreur brute, toujours connecté à PADOC. Il ne pouvait donc pas changer de
 * compte : la connexion suivante le reprenait d'office.</p>
 *
 * <p>Vouloir se déconnecter ne demande aucune preuve : la session est fermée
 * dans tous les cas. Seule la destination reste prudente — jamais l'adresse
 * fournie par la requête, puisque c'est elle qui a pu être refusée, mais la
 * première adresse de retour enregistrée pour ce client, ou à défaut le
 * formulaire de connexion de PADOC.</p>
 */
public class DeconnexionTolerante implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(DeconnexionTolerante.class);

    private final RegisteredClientRepository clients;
    private final String pageConnexion;

    public DeconnexionTolerante(RegisteredClientRepository clients, String pageConnexion) {
        this.clients = clients;
        this.pageConnexion = pageConnexion;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest requete,
            HttpServletResponse reponse,
            AuthenticationException refus
    ) throws IOException {
        // Journalisé : c'est le seul endroit où la cause du refus se voit.
        String motif = refus instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode() + " — " + oauth.getError().getDescription()
                : refus.getMessage();
        log.warn("Fédération : déconnexion refusée ({}), session fermée malgré tout.", motif);

        new SecurityContextLogoutHandler()
                .logout(requete, reponse, SecurityContextHolder.getContext().getAuthentication());

        reponse.sendRedirect(destination(requete.getParameter("client_id")));
    }

    String destination(String clientId) {
        RegisteredClient client = clientId == null || clientId.isBlank()
                ? null
                : clients.findByClientId(clientId);
        if (client == null || client.getPostLogoutRedirectUris().isEmpty()) {
            return pageConnexion + "?deconnecte";
        }
        return client.getPostLogoutRedirectUris().stream().sorted().findFirst().orElseThrow();
    }
}
