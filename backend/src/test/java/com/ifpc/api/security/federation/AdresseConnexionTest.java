package com.ifpc.api.security.federation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * L'adresse vers laquelle on renvoie un visiteur non authentifié.
 *
 * <p>Ce qui se joue ici n'est pas cosmétique. Avec un chemin relatif, le
 * conteneur fabrique la redirection à partir de l'hôte qu'il voit — l'hôte
 * interne quand un relais est devant. Le navigateur change alors de domaine,
 * le cookie de session posé sur le domaine public ne le suit pas, et la
 * demande d'autorisation mémorisée disparaît. La connexion réussit, puis
 * l'utilisateur atterrit sur « / », sans aucune indication.</p>
 */
class AdresseConnexionTest {

    private static String adresse(String emetteur) throws Exception {
        Method m = ConfigurationFederation.class
                .getDeclaredMethod("adresseConnexion", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, emetteur);
    }

    @Test
    void sans_emetteur_le_chemin_reste_relatif() throws Exception {
        // Développement local : un seul hôte, aucun relais, rien à préfixer.
        assertEquals(ConfigurationFederation.CHEMIN_CONNEXION, adresse(null));
        assertEquals(ConfigurationFederation.CHEMIN_CONNEXION, adresse(""));
        assertEquals(ConfigurationFederation.CHEMIN_CONNEXION, adresse("   "));
    }

    @Test
    void avec_emetteur_l_adresse_est_absolue() throws Exception {
        assertEquals("https://ifpc.vercel.app/federation/connexion",
                adresse("https://ifpc.vercel.app"));
    }

    @Test
    void la_barre_finale_de_l_emetteur_ne_double_pas() throws Exception {
        // « https://exemple.fr//federation/connexion » est une autre adresse :
        // le cookie de session s'y applique, mais la correspondance exacte des
        // chemins, elle, ne pardonne pas.
        assertEquals("https://exemple.fr/federation/connexion",
                adresse("https://exemple.fr/"));
        assertEquals("https://exemple.fr/federation/connexion",
                adresse("https://exemple.fr///"));
    }

    @Test
    void un_emetteur_avec_port_est_conserve() throws Exception {
        assertEquals("http://localhost:8080/federation/connexion",
                adresse("http://localhost:8080"));
    }
}
