package com.ifpc.api.services;

import com.ifpc.api.models.Fonctionnalite;
import com.ifpc.api.repositories.FonctionnaliteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Les règles qui, si elles cassaient, le feraient silencieusement : un
 * déploiement qui rallume ce qu'un administrateur vient d'éteindre, ou un
 * administrateur privé de ce qu'il doit préparer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceFonctionnalitesTest {

    @Mock private FonctionnaliteRepository depot;
    @InjectMocks private ServiceFonctionnalites service;

    private static Fonctionnalite fermee(String cle) {
        return Fonctionnalite.builder().cle(cle).libelle(cle).activee(false).build();
    }

    private static Fonctionnalite ouverte(String cle) {
        return Fonctionnalite.builder().cle(cle).libelle(cle).activee(true).build();
    }

    // ── Amorçage ────────────────────────────────────────────────────────────

    @Test
    void amorcer_inscrit_les_fonctionnalites_absentes() {
        when(depot.existsById(any())).thenReturn(false);

        service.amorcer();

        verify(depot, times(ServiceFonctionnalites.CATALOGUE.size())).save(any());
    }

    @Test
    void amorcer_ne_rallume_jamais_ce_qui_est_deja_en_base() {
        // Le scénario qui compte : un administrateur ferme la colorimétrie,
        // puis un déploiement a lieu. Sans cette garantie, sa décision serait
        // défaite au redémarrage, sans trace et sans que personne ne comprenne.
        when(depot.existsById(any())).thenReturn(true);

        service.amorcer();

        verify(depot, never()).save(any());
    }

    @Test
    void une_nouvelle_fonctionnalite_arrive_ouverte() {
        when(depot.existsById(any())).thenReturn(false);

        service.amorcer();

        verify(depot, atLeastOnce()).save(argThat(f -> Boolean.TRUE.equals(f.getActivee())));
    }

    // ── Lecture ─────────────────────────────────────────────────────────────

    @Test
    void un_administrateur_voit_tout_meme_ce_qui_est_ferme() {
        when(depot.findById(any())).thenAnswer(i -> Optional.of(fermee(i.getArgument(0))));

        Map<String, Boolean> etat = service.etatPour(true);

        assertEquals(ServiceFonctionnalites.CATALOGUE.size(), etat.size());
        assertTrue(etat.values().stream().allMatch(Boolean::booleanValue),
                "un administrateur doit pouvoir préparer une fonctionnalité fermée au public");
    }

    @Test
    void le_public_ne_voit_pas_ce_qui_est_ferme() {
        when(depot.findById("colorimetrie")).thenReturn(Optional.of(fermee("colorimetrie")));
        when(depot.findById("cuves")).thenReturn(Optional.of(ouverte("cuves")));
        when(depot.findById(argThat(c -> !"colorimetrie".equals(c) && !"cuves".equals(c))))
                .thenAnswer(i -> Optional.of(ouverte(i.getArgument(0))));

        Map<String, Boolean> etat = service.etatPour(false);

        assertFalse(etat.get("colorimetrie"));
        assertTrue(etat.get("cuves"));
    }

    @Test
    void une_cle_absente_de_la_base_est_consideree_ouverte() {
        // Fonctionnalité déployée mais pas encore amorcée : on n'ampute pas
        // l'outil sur un simple décalage de démarrage.
        when(depot.findById(any())).thenReturn(Optional.empty());

        assertTrue(service.estAccessible("colorimetrie", false));
    }

    @Test
    void une_cle_inconnue_du_catalogue_ne_ferme_rien() {
        // Après le renommage d'une clé, un écran ne doit pas disparaître sans
        // que personne ne l'ait décidé.
        assertTrue(service.estAccessible("fonctionnalite-renommee", false));
        verify(depot, never()).findById("fonctionnalite-renommee");
    }

    @Test
    void estAccessible_refuse_au_public_ce_qui_est_ferme() {
        when(depot.findById("assistant")).thenReturn(Optional.of(fermee("assistant")));

        assertFalse(service.estAccessible("assistant", false));
        assertTrue(service.estAccessible("assistant", true));
    }

    // ── Écriture ────────────────────────────────────────────────────────────

    @Test
    void basculer_enregistre_l_auteur_et_la_date() {
        when(depot.findById("cuves")).thenReturn(Optional.of(ouverte("cuves")));
        when(depot.save(any())).thenAnswer(i -> i.getArgument(0));

        Fonctionnalite f = service.basculer("cuves", false, "admin@ifpc.com");

        assertFalse(f.getActivee());
        assertEquals("admin@ifpc.com", f.getModifiePar());
        assertNotNull(f.getModifieLe(), "sans date, impossible de savoir quand l'accès a changé");
    }

    @Test
    void basculer_refuse_une_cle_hors_catalogue() {
        // Le catalogue décrit ce qui existe réellement : accepter n'importe
        // quelle clé laisserait croire à un réglage sans effet.
        assertThrows(IllegalArgumentException.class,
                () -> service.basculer("inventee", false, "admin@ifpc.com"));
        verify(depot, never()).save(any());
    }

    @Test
    void basculer_cree_la_ligne_si_elle_manque() {
        when(depot.findById("historique")).thenReturn(Optional.empty());
        when(depot.save(any())).thenAnswer(i -> i.getArgument(0));

        Fonctionnalite f = service.basculer("historique", false, "admin@ifpc.com");

        assertEquals("historique", f.getCle());
        assertFalse(f.getActivee());
        assertNotNull(f.getLibelle(), "le libellé vient du catalogue, pas d'une saisie");
    }
}
