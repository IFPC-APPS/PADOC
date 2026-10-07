package com.ifpc.api.security.federation;

import com.ifpc.api.models.HabilitationPlateforme;
import com.ifpc.api.models.Role;
import com.ifpc.api.models.User;
import com.ifpc.api.repositories.HabilitationPlateformeRepository;
import com.ifpc.api.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Remplit les claims des jetons émis aux plateformes fédérées.
 *
 * <p>Trois décisions y sont appliquées, chacune irréversible ou sensible :</p>
 *
 * <ol>
 *   <li><b>{@code sub} = identifiant externe, jamais l'e-mail</b> (spec §7.1).
 *       Par défaut le serveur d'autorisation met le nom du principal, donc
 *       l'adresse e-mail. Une plateforme cliente qui rattacherait ses comptes
 *       à cette valeur les perdrait au premier changement d'adresse.</li>
 *   <li><b>Les rôles émis sont ceux de la plateforme destinataire</b>
 *       (spec §7.2), lus dans la table d'habilitation. Le rôle IFPC
 *       ({@code EXPERT}, {@code ADMIN}…) n'est jamais propagé : il n'a de sens
 *       que sur PADOC. Une seule passerelle, explicite : un administrateur
 *       PADOC reçoit en plus les rôles déclarés dans
 *       {@code FEDERATION_CLIENT_ROLES_ADMIN} pour la plateforme déclarée —
 *       sans quoi il faudrait l'habiliter à la main pour administrer l'outil
 *       qu'il vient de raccorder.</li>
 *   <li><b>Les claims d'identité suivent les portées accordées.</b> Une
 *       plateforme qui n'a pas demandé {@code email} ne le reçoit pas.</li>
 * </ol>
 */
@Component
public class PersonnalisationJeton implements OAuth2TokenCustomizer<JwtEncodingContext> {

    /**
     * Espace de noms des claims privés.
     *
     * <p>Un claim non standard doit porter un nom qui ne puisse jamais entrer
     * en collision avec un futur claim normalisé — d'où l'URI. Un simple
     * {@code roles} serait un pari sur l'évolution des spécifications.</p>
     */
    public static final String CLAIM_ROLES = "https://ifpc.eu/claims/roles";
    public static final String CLAIM_ORGANISATION = "https://ifpc.eu/claims/organisation";

    private final UserRepository utilisateurs;
    private final HabilitationPlateformeRepository habilitations;
    private final String clientDeclare;
    private final Set<String> rolesAdmin;

    @Autowired
    public PersonnalisationJeton(
            UserRepository utilisateurs,
            HabilitationPlateformeRepository habilitations,
            @Value("${federation.client.id:}") String clientDeclare,
            @Value("${federation.client.roles-admin:}") String rolesAdmin
    ) {
        this.utilisateurs = utilisateurs;
        this.habilitations = habilitations;
        this.clientDeclare = clientDeclare;
        this.rolesAdmin = rolesAdmin == null ? Set.of() : Arrays.stream(rolesAdmin.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Sans rôle accordé d'office aux administrateurs. */
    public PersonnalisationJeton(UserRepository utilisateurs, HabilitationPlateformeRepository habilitations) {
        this(utilisateurs, habilitations, "", "");
    }

    @Override
    public void customize(JwtEncodingContext contexte) {
        String nomPrincipal = contexte.getPrincipal().getName();
        User utilisateur = utilisateurs.findByEmail(nomPrincipal).orElse(null);
        if (utilisateur == null) {
            return;
        }

        // Vaut pour les deux jetons : le sujet doit être le même partout, sans
        // quoi la plateforme cliente rattacherait ses données à deux clés
        // différentes selon le jeton qu'elle lit.
        contexte.getClaims().subject(utilisateur.getExternalId());

        String clientId = contexte.getRegisteredClient().getClientId();
        Set<String> portees = contexte.getAuthorizedScopes();

        if (OAuth2TokenType.ACCESS_TOKEN.equals(contexte.getTokenType())) {
            contexte.getClaims().claim(CLAIM_ROLES, rolesPour(utilisateur, clientId));
            return;
        }

        if ("id_token".equals(contexte.getTokenType().getValue())) {
            remplirIdentite(contexte, utilisateur, portees);
            contexte.getClaims().claim(CLAIM_ROLES, rolesPour(utilisateur, clientId));
        }
    }

    private void remplirIdentite(JwtEncodingContext contexte, User utilisateur, Set<String> portees) {
        if (portees.contains("email")) {
            contexte.getClaims().claim("email", utilisateur.getEmail());
            // Aucun parcours de vérification d'adresse n'existe aujourd'hui :
            // l'inscription ne demande pas de confirmer l'e-mail. Annoncer
            // « vérifié » serait faux, et une plateforme cliente pourrait s'en
            // servir pour rattacher un compte existant — donc pour en prendre
            // le contrôle (spec §7.4).
            contexte.getClaims().claim("email_verified", false);
        }
        if (portees.contains("profile")) {
            ajouterSiRenseigne(contexte, "given_name", utilisateur.getFirstName());
            ajouterSiRenseigne(contexte, "family_name", utilisateur.getLastName());
            ajouterSiRenseigne(contexte, "name", nomComplet(utilisateur));
            // Déclaratif : champ de profil libre, saisi et modifiable par
            // l'utilisateur lui-même, sans référentiel. Utilisable pour
            // afficher un rattachement, jamais comme frontière d'autorisation
            // côté plateforme cliente (spec §6.4).
            ajouterSiRenseigne(contexte, CLAIM_ORGANISATION, utilisateur.getCompanyName());
        }
    }

    /**
     * N'ajoute le claim que s'il a une valeur.
     *
     * <p>{@code JwtClaimsSet} refuse une valeur nulle, et tous ces champs de
     * profil sont facultatifs — le compte administrateur initial, par exemple,
     * n'a pas de société. Émettre un claim vide n'apporterait rien de toute
     * façon : son absence dit la même chose.</p>
     */
    private static void ajouterSiRenseigne(JwtEncodingContext contexte, String nom, String valeur) {
        if (valeur != null && !valeur.isBlank()) {
            contexte.getClaims().claim(nom, valeur);
        }
    }

    private Set<String> rolesPour(User utilisateur, String clientId) {
        Set<String> roles = new LinkedHashSet<>(
                habilitations.findByUtilisateurIdAndClientId(utilisateur.getId(), clientId)
                        .map(HabilitationPlateforme::rolesEnSet)
                        .orElse(Set.of()));
        // Limité à la plateforme déclarée : ces rôles sont dans SON vocabulaire,
        // ils n'auraient aucun sens — ou un sens imprévu — chez une autre.
        if (utilisateur.getRole() == Role.ADMIN && clientId.equals(clientDeclare)) {
            roles.addAll(rolesAdmin);
        }
        return roles;
    }

    private static String nomComplet(User utilisateur) {
        String complet = ((utilisateur.getFirstName() == null ? "" : utilisateur.getFirstName())
                + " " + (utilisateur.getLastName() == null ? "" : utilisateur.getLastName())).trim();
        return complet.isEmpty() ? null : complet;
    }
}
