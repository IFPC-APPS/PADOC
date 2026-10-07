package com.ifpc.api.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Rattachement d'une URL à sa fonctionnalité.
 *
 * <p>Le risque n'est pas qu'une route soit mal fermée : c'est qu'une route
 * voisine le soit par accident, ou qu'une route sensible échappe au contrôle.
 * Les deux se voient mal à l'œil nu.</p>
 */
class GardeFonctionnalitesTest {

    @Test
    void rattache_une_route_exacte() {
        assertEquals("cuves", GardeFonctionnalites.cleDe("/api/cuves"));
        assertEquals("historique", GardeFonctionnalites.cleDe("/api/history"));
    }

    @Test
    void rattache_les_sous_chemins() {
        assertEquals("cuves", GardeFonctionnalites.cleDe("/api/cuves/12"));
        assertEquals("cuves", GardeFonctionnalites.cleDe("/api/operations/3/annuler"));
    }

    @Test
    void ne_capture_pas_une_route_au_nom_voisin() {
        // « /api/lots » ne doit pas emporter « /api/lotsdivers » : une route
        // sans rapport cesserait de répondre, et le lien avec la fonctionnalité
        // fermée serait indevinable.
        assertNull(GardeFonctionnalites.cleDe("/api/lotsdivers"));
        assertNull(GardeFonctionnalites.cleDe("/api/cuvesphere"));
    }

    @Test
    void laisse_passer_ce_qui_n_est_pas_rattache() {
        assertNull(GardeFonctionnalites.cleDe("/api/auth/login"));
        assertNull(GardeFonctionnalites.cleDe("/api/config/fonctionnalites"));
        assertNull(GardeFonctionnalites.cleDe("/api/admin/users"));
    }

    @Test
    void la_configuration_ne_ferme_jamais_l_authentification_ni_l_administration() {
        // Fermer l'une de ces routes enfermerait tout le monde dehors, y
        // compris l'administrateur venu rouvrir la fonctionnalité.
        for (String chemin : new String[]{
                "/api/auth/login", "/api/auth/me", "/api/admin/fonctionnalites",
                "/api/config/fonctionnalites"}) {
            assertNull(GardeFonctionnalites.cleDe(chemin), chemin + " doit rester joignable");
        }
    }

    @Test
    void tolere_une_uri_nulle() {
        assertNull(GardeFonctionnalites.cleDe(null));
    }
}
