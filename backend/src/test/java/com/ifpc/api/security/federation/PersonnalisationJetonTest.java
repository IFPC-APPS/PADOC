package com.ifpc.api.security.federation;

import com.ifpc.api.models.HabilitationPlateforme;
import com.ifpc.api.models.Role;
import com.ifpc.api.models.User;
import com.ifpc.api.repositories.HabilitationPlateformeRepository;
import com.ifpc.api.repositories.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Ce que reçoit — et ne reçoit pas — une plateforme fédérée.
 *
 * <p>Trois invariants y sont vérifiés, chacun correspondant à une décision de
 * la spécification qu'une régression rendrait silencieuse : le sujet n'est
 * jamais l'e-mail, les rôles ne franchissent pas la frontière d'une plateforme,
 * et un claim non demandé n'est pas émis.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersonnalisationJetonTest {

    private static final String ID_EXTERNE = "6f1b1e2c-9a44-4c2f-8f0e-2d9f1b7c3a51";

    @Mock private UserRepository utilisateurs;
    @Mock private HabilitationPlateformeRepository habilitations;

    private PersonnalisationJeton personnalisation() {
        return new PersonnalisationJeton(utilisateurs, habilitations);
    }

    private User utilisateur() {
        return User.builder()
                .id(7L)
                .externalId(ID_EXTERNE)
                .email("producteur@exemple.fr")
                .firstName("Camille")
                .lastName("Renaud")
                .companyName("Cidrerie du Bocage")
                .role(Role.EXPERT)
                .enabled(true)
                .build();
    }

    private RegisteredClient client(String clientId) {
        return RegisteredClient.withId("interne-" + clientId)
                .clientId(clientId)
                .clientName(clientId)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://exemple.fr/callback")
                .scope("openid")
                .clientSettings(ClientSettings.builder().build())
                .build();
    }

    private JwtEncodingContext contexte(User u, String clientId, OAuth2TokenType type, Set<String> portees) {
        return JwtEncodingContext
                .with(JwsHeader.with(() -> "RS256"), JwtClaimsSet.builder().subject(u.getEmail()))
                .registeredClient(client(clientId))
                .principal(new UsernamePasswordAuthenticationToken(u, null, List.of()))
                .authorizedScopes(portees)
                .tokenType(type)
                .build();
    }

    @Test
    @DisplayName("le sujet du jeton est l'identifiant externe, jamais l'adresse e-mail")
    void sujetEstIdentifiantExterne() {
        User u = utilisateur();
        when(utilisateurs.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
        when(habilitations.findByUtilisateurIdAndClientId(7L, "analyse")).thenReturn(Optional.empty());

        JwtEncodingContext ctx = contexte(u, "analyse", OAuth2TokenType.ACCESS_TOKEN, Set.of("openid"));
        personnalisation().customize(ctx);

        JwtClaimsSet claims = ctx.getClaims().build();
        assertEquals(ID_EXTERNE, claims.getSubject(),
                "un sub fondé sur l'e-mail casserait les rattachements au premier changement d'adresse");
        assertNotEquals(u.getEmail(), claims.getSubject());
    }

    @Test
    @DisplayName("les rôles émis sont ceux de la plateforme destinataire, pas le rôle IFPC")
    void rolesPropresALaPlateforme() {
        User u = utilisateur();   // EXPERT chez IFPC
        when(utilisateurs.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
        when(habilitations.findByUtilisateurIdAndClientId(7L, "analyse"))
                .thenReturn(Optional.of(HabilitationPlateforme.builder()
                        .utilisateur(u).clientId("analyse").roles("degustateur, referent").build()));

        JwtEncodingContext ctx = contexte(u, "analyse", OAuth2TokenType.ACCESS_TOKEN, Set.of("openid"));
        personnalisation().customize(ctx);

        Object roles = ctx.getClaims().build().getClaim(PersonnalisationJeton.CLAIM_ROLES);
        assertEquals(Set.of("degustateur", "referent"), roles);
        assertFalse(roles.toString().contains("EXPERT"),
                "le rôle IFPC n'a pas de sens sur une autre plateforme et ne doit pas fuir");
    }

    @Test
    @DisplayName("un administrateur PADOC reçoit d'office les rôles déclarés pour la plateforme")
    void administrateurRecoitRolesDeclares() {
        User u = utilisateur();
        u.setRole(Role.ADMIN);
        when(utilisateurs.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
        when(habilitations.findByUtilisateurIdAndClientId(7L, "ciderscope"))
                .thenReturn(Optional.of(HabilitationPlateforme.builder()
                        .utilisateur(u).clientId("ciderscope").roles("creneaux").build()));

        JwtEncodingContext ctx = contexte(u, "ciderscope", OAuth2TokenType.ACCESS_TOKEN, Set.of("openid"));
        new PersonnalisationJeton(utilisateurs, habilitations, "ciderscope", "animateur").customize(ctx);

        assertEquals(Set.of("creneaux", "animateur"),
                ctx.getClaims().build().getClaim(PersonnalisationJeton.CLAIM_ROLES));
    }

    @Test
    @DisplayName("les rôles d'administrateur ne valent que pour la plateforme déclarée, et que pour un ADMIN")
    void rolesAdminCirconscrits() {
        User admin = utilisateur();
        admin.setRole(Role.ADMIN);
        when(utilisateurs.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(habilitations.findByUtilisateurIdAndClientId(eq(7L), anyString())).thenReturn(Optional.empty());
        PersonnalisationJeton p = new PersonnalisationJeton(utilisateurs, habilitations, "ciderscope", "animateur");

        JwtEncodingContext autre = contexte(admin, "autre", OAuth2TokenType.ACCESS_TOKEN, Set.of("openid"));
        p.customize(autre);
        assertEquals(Set.of(), autre.getClaims().build().getClaim(PersonnalisationJeton.CLAIM_ROLES));

        User expert = utilisateur();
        when(utilisateurs.findByEmail(expert.getEmail())).thenReturn(Optional.of(expert));
        JwtEncodingContext ctx = contexte(expert, "ciderscope", OAuth2TokenType.ACCESS_TOKEN, Set.of("openid"));
        p.customize(ctx);
        assertEquals(Set.of(), ctx.getClaims().build().getClaim(PersonnalisationJeton.CLAIM_ROLES));
    }

    @Test
    @DisplayName("sans habilitation, aucun rôle n'est émis")
    void aucunRoleSansHabilitation() {
        User u = utilisateur();
        when(utilisateurs.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
        when(habilitations.findByUtilisateurIdAndClientId(7L, "autre")).thenReturn(Optional.empty());

        JwtEncodingContext ctx = contexte(u, "autre", OAuth2TokenType.ACCESS_TOKEN, Set.of("openid"));
        personnalisation().customize(ctx);

        assertEquals(Set.of(), ctx.getClaims().build().getClaim(PersonnalisationJeton.CLAIM_ROLES));
    }

    @Test
    @DisplayName("le jeton d'identité ne porte l'e-mail que si la portée « email » a été accordée")
    void claimsSuivantLesPortees() {
        User u = utilisateur();
        when(utilisateurs.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
        when(habilitations.findByUtilisateurIdAndClientId(7L, "analyse")).thenReturn(Optional.empty());

        JwtEncodingContext sansEmail = contexte(
                u, "analyse", new OAuth2TokenType("id_token"), Set.of("openid"));
        personnalisation().customize(sansEmail);
        assertNull(sansEmail.getClaims().build().getClaim("email"));

        JwtEncodingContext avecEmail = contexte(
                u, "analyse", new OAuth2TokenType("id_token"), Set.of("openid", "email"));
        personnalisation().customize(avecEmail);
        assertEquals("producteur@exemple.fr", avecEmail.getClaims().build().getClaim("email"));
    }

    @Test
    @DisplayName("email_verified est faux : aucun parcours de vérification d'adresse n'existe")
    void emailJamaisAnnonceVerifie() {
        User u = utilisateur();
        when(utilisateurs.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
        when(habilitations.findByUtilisateurIdAndClientId(7L, "analyse")).thenReturn(Optional.empty());

        JwtEncodingContext ctx = contexte(
                u, "analyse", new OAuth2TokenType("id_token"), Set.of("openid", "email"));
        personnalisation().customize(ctx);

        assertEquals(Boolean.FALSE, ctx.getClaims().build().getClaim("email_verified"),
                "annoncer « vérifié » permettrait un rattachement de compte par e-mail, "
                        + "donc une prise de contrôle");
    }

    @Test
    @DisplayName("l'organisation accompagne la portée « profile », en restant déclarative")
    void organisationAvecProfile() {
        User u = utilisateur();
        when(utilisateurs.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
        when(habilitations.findByUtilisateurIdAndClientId(7L, "analyse")).thenReturn(Optional.empty());

        JwtEncodingContext ctx = contexte(
                u, "analyse", new OAuth2TokenType("id_token"), Set.of("openid", "profile"));
        personnalisation().customize(ctx);

        JwtClaimsSet claims = ctx.getClaims().build();
        assertEquals("Cidrerie du Bocage", claims.getClaim(PersonnalisationJeton.CLAIM_ORGANISATION));
        assertEquals("Camille", claims.getClaim("given_name"));
        assertEquals("Camille Renaud", claims.getClaim("name"));
    }

    @Test
    @DisplayName("un profil aux champs vides n'émet pas de claim nul")
    void profilIncompletNEmetPasDeClaimNul() {
        // Cas réel rencontré à l'exécution : le compte administrateur initial
        // n'a pas de société, et JwtClaimsSet refuse une valeur nulle — la
        // génération du jeton échouait en 500 sur le point d'entrée /oauth2/token.
        User sansProfil = User.builder()
                .id(2L).externalId("id-2").email("brut@exemple.fr")
                .role(Role.USER).enabled(true)
                .build();
        when(utilisateurs.findByEmail(sansProfil.getEmail())).thenReturn(Optional.of(sansProfil));
        when(habilitations.findByUtilisateurIdAndClientId(2L, "analyse")).thenReturn(Optional.empty());

        JwtEncodingContext ctx = contexte(
                sansProfil, "analyse", new OAuth2TokenType("id_token"), Set.of("openid", "profile", "email"));

        assertDoesNotThrow(() -> personnalisation().customize(ctx));

        JwtClaimsSet claims = ctx.getClaims().build();
        assertNull(claims.getClaim("given_name"));
        assertNull(claims.getClaim(PersonnalisationJeton.CLAIM_ORGANISATION));
        assertEquals("id-2", claims.getSubject(), "le sujet reste émis, lui n'est jamais vide");
        assertEquals("brut@exemple.fr", claims.getClaim("email"));
    }

    @Test
    @DisplayName("un principal inconnu en base ne fait pas échouer l'émission")
    void utilisateurInconnuSansEffet() {
        when(utilisateurs.findByEmail("fantome@exemple.fr")).thenReturn(Optional.empty());

        User fantome = User.builder().id(99L).email("fantome@exemple.fr").role(Role.USER).build();
        JwtEncodingContext ctx = contexte(fantome, "analyse", OAuth2TokenType.ACCESS_TOKEN, Set.of("openid"));

        assertDoesNotThrow(() -> personnalisation().customize(ctx));
        assertNull(ctx.getClaims().build().getClaim(PersonnalisationJeton.CLAIM_ROLES));
    }

    @Test
    @DisplayName("le type de jeton d'accès reste celui attendu par la spécification OAuth")
    void typeJetonAcces() {
        assertEquals(OAuth2AccessToken.TokenType.BEARER.getValue(), "Bearer");
    }
}
