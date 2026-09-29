package com.ifpc.api.security.federation;

import com.ifpc.api.models.HabilitationPlateforme;
import com.ifpc.api.models.Role;
import com.ifpc.api.models.User;
import com.ifpc.api.repositories.HabilitationPlateformeRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Les deux refus prononcés avant qu'un code d'autorisation ne soit émis.
 *
 * <p>Ce sont les contrôles qui empêchent la fédération de devenir une porte
 * d'entrée plus permissive que la connexion directe à PADOC.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ControleAccesPlateformeTest {

    private static final String CHEMIN = "/oauth2/authorize";

    @Mock private HabilitationPlateformeRepository habilitations;
    @Mock private FilterChain chaine;

    @AfterEach
    void nettoyer() {
        SecurityContextHolder.clearContext();
    }

    private ControleAccesPlateforme filtre() {
        return new ControleAccesPlateforme(habilitations, CHEMIN);
    }

    private MockHttpServletRequest requete(String clientId) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", CHEMIN);
        r.setServletPath(CHEMIN);
        if (clientId != null) {
            r.setParameter("client_id", clientId);
        }
        return r;
    }

    private void connecter(User u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    private User utilisateur(Role role) {
        return User.builder()
                .id(4L).externalId("id-externe").email("a@b.fr")
                .role(role).enabled(true)
                .build();
    }

    @Test
    @DisplayName("un compte en attente de validation est refusé, même si son drapeau est actif")
    void comptePendingRefuse() throws Exception {
        // Cas réel : AdminController.updateUserRole peut rétrograder vers
        // PENDING sans toucher « enabled ». Une session ouverte avant la
        // rétrogradation ne doit pas continuer d'ouvrir des accès.
        connecter(utilisateur(Role.PENDING));
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        filtre().doFilter(requete("analyse"), reponse, chaine);

        assertEquals(403, reponse.getStatus());
        assertTrue(reponse.getContentAsString().contains("attente de validation"));
        verify(chaine, never()).doFilter(any(), any());
        verifyNoInteractions(habilitations);
    }

    @Test
    @DisplayName("sans habilitation sur la plateforme demandée, l'accès est refusé")
    void sansHabilitationRefuse() throws Exception {
        connecter(utilisateur(Role.USER));
        when(habilitations.findByUtilisateurIdAndClientId(4L, "analyse")).thenReturn(Optional.empty());
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        filtre().doFilter(requete("analyse"), reponse, chaine);

        assertEquals(403, reponse.getStatus());
        assertTrue(reponse.getContentAsString().contains("pas encore reçu l'accès"));
        verify(chaine, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("avec habilitation, le parcours continue")
    void avecHabilitationPasse() throws Exception {
        User u = utilisateur(Role.USER);
        connecter(u);
        when(habilitations.findByUtilisateurIdAndClientId(4L, "analyse"))
                .thenReturn(Optional.of(HabilitationPlateforme.builder()
                        .utilisateur(u).clientId("analyse").roles("degustateur").build()));
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        filtre().doFilter(requete("analyse"), reponse, chaine);

        assertEquals(200, reponse.getStatus());
        verify(chaine).doFilter(any(), any());
    }

    @Test
    @DisplayName("l'habilitation est vérifiée plateforme par plateforme")
    void habilitationNeFranchitPasLesPlateformes() throws Exception {
        User u = utilisateur(Role.USER);
        connecter(u);
        when(habilitations.findByUtilisateurIdAndClientId(4L, "analyse"))
                .thenReturn(Optional.of(HabilitationPlateforme.builder()
                        .utilisateur(u).clientId("analyse").build()));
        when(habilitations.findByUtilisateurIdAndClientId(4L, "autre-outil"))
                .thenReturn(Optional.empty());
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        filtre().doFilter(requete("autre-outil"), reponse, chaine);

        assertEquals(403, reponse.getStatus());
    }

    @Test
    @DisplayName("un visiteur non connecté est laissé au serveur d'autorisation, qui le redirige")
    void visiteurAnonymeLaissePasser() throws Exception {
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        filtre().doFilter(requete("analyse"), reponse, chaine);

        verify(chaine).doFilter(any(), any());
        verifyNoInteractions(habilitations);
    }

    @Test
    @DisplayName("une requête sans client_id est laissée au serveur, qui produit l'erreur normalisée")
    void sansClientIdLaissePasser() throws Exception {
        connecter(utilisateur(Role.USER));
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        filtre().doFilter(requete(null), reponse, chaine);

        verify(chaine).doFilter(any(), any());
    }

    @Test
    @DisplayName("le filtre ne s'applique qu'au point d'entrée d'autorisation")
    void filtreCibleLeSeulCheminUtile() throws Exception {
        connecter(utilisateur(Role.PENDING));
        MockHttpServletRequest ailleurs = new MockHttpServletRequest("POST", "/oauth2/token");
        ailleurs.setServletPath("/oauth2/token");
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        filtre().doFilter(ailleurs, reponse, chaine);

        // L'échange de code n'a pas de session : y rejouer le contrôle
        // n'apporterait rien et bloquerait le rafraîchissement.
        verify(chaine).doFilter(any(), any());
        assertEquals(200, reponse.getStatus());
    }
}
