package com.ifpc.api.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Les invariants du compte qui rendent la fédération sûre.
 *
 * <p>Deux d'entre eux ferment des défauts réels : un compte rétrogradé vers
 * {@code PENDING} restait authentifiable, et l'identité exposée aurait été
 * l'adresse e-mail.</p>
 */
class IdentiteFedereeTest {

    private User compte(Role role, boolean actif) {
        return User.builder()
                .email("a@b.fr").password("x")
                .role(role).enabled(actif)
                .build();
    }

    @Test
    @DisplayName("un identifiant externe est attribué à la création")
    void identifiantExterneAttribue() {
        User u = compte(Role.USER, true);
        assertNull(u.getExternalId(), "rien n'est tiré avant la persistance");

        ReflectionTestUtils.invokeMethod(u, "attribuerIdentifiantExterne");

        assertNotNull(u.getExternalId());
        assertEquals(36, u.getExternalId().length(), "un UUID canonique fait 36 caractères");
        assertDoesNotThrow(() -> java.util.UUID.fromString(u.getExternalId()));
    }

    @Test
    @DisplayName("un identifiant externe déjà attribué n'est jamais réécrit")
    void identifiantExterneJamaisReecrit() {
        User u = compte(Role.USER, true);
        u.setExternalId("valeur-deja-connue-d-une-plateforme");

        ReflectionTestUtils.invokeMethod(u, "attribuerIdentifiantExterne");

        assertEquals("valeur-deja-connue-d-une-plateforme", u.getExternalId(),
                "le réécrire casserait les rattachements côté plateforme cliente");
    }

    @Test
    @DisplayName("deux comptes reçoivent deux identifiants distincts")
    void identifiantsDistincts() {
        User a = compte(Role.USER, true);
        User b = compte(Role.USER, true);
        ReflectionTestUtils.invokeMethod(a, "attribuerIdentifiantExterne");
        ReflectionTestUtils.invokeMethod(b, "attribuerIdentifiantExterne");

        assertNotEquals(a.getExternalId(), b.getExternalId());
    }

    @Test
    @DisplayName("un compte PENDING ne s'authentifie pas, même avec le drapeau actif")
    void pendingNeSAuthentifiePas() {
        // Le cas atteignable : AdminController.updateUserRole rétrograde vers
        // PENDING sans remettre « enabled » à faux.
        assertFalse(compte(Role.PENDING, true).isEnabled(),
                "sinon le formulaire de connexion du parcours OAuth accepterait un compte non validé");
    }

    @Test
    @DisplayName("un compte désactivé ne s'authentifie pas, quel que soit son rôle")
    void desactiveNeSAuthentifiePas() {
        assertFalse(compte(Role.ADMIN, false).isEnabled());
        assertFalse(compte(Role.USER, false).isEnabled());
    }

    @Test
    @DisplayName("un compte validé et actif s'authentifie")
    void compteValideSAuthentifie() {
        assertTrue(compte(Role.USER, true).isEnabled());
        assertTrue(compte(Role.EXPERT, true).isEnabled());
        assertTrue(compte(Role.ADMIN, true).isEnabled());
    }

    @Test
    @DisplayName("les rôles d'habilitation se lisent en liste, vides et espaces écartés")
    void rolesDHabilitation() {
        HabilitationPlateforme h = HabilitationPlateforme.builder()
                .clientId("analyse").roles(" degustateur , , referent ").build();

        assertEquals(java.util.Set.of("degustateur", "referent"), h.rolesEnSet());
    }

    @Test
    @DisplayName("une habilitation sans rôle reste un accès valide")
    void habilitationSansRole() {
        // L'accès à la plateforme et les rôles qu'on y tient sont deux
        // questions distinctes : on peut entrer sans rôle particulier.
        assertEquals(java.util.Set.of(),
                HabilitationPlateforme.builder().clientId("analyse").roles("").build().rolesEnSet());
        assertEquals(java.util.Set.of(),
                HabilitationPlateforme.builder().clientId("analyse").build().rolesEnSet());
    }
}
