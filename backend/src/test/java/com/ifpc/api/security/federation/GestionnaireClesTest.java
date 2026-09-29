package com.ifpc.api.security.federation;

import com.ifpc.api.models.CleSignature;
import com.ifpc.api.repositories.CleSignatureRepository;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * La clé de signature : présence, persistance, rotation.
 *
 * <p>Ce qui est vérifié ici tient à une propriété unique : les plateformes
 * clientes ne doivent jamais pouvoir signer, et ne doivent jamais cesser de
 * pouvoir vérifier.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GestionnaireClesTest {

    @Mock private CleSignatureRepository depot;

    /** Un dépôt en mémoire, pour observer ce qui est réellement écrit. */
    private List<CleSignature> enBase() {
        List<CleSignature> stockees = new ArrayList<>();
        when(depot.save(any(CleSignature.class))).thenAnswer(appel -> {
            CleSignature c = appel.getArgument(0);
            stockees.removeIf(existante -> existante.getKid().equals(c.getKid()));
            stockees.add(c);
            return c;
        });
        when(depot.findAllByOrderByCreeeLeDesc()).thenAnswer(appel -> stockees.stream()
                .sorted((a, b) -> b.getCreeeLe().compareTo(a.getCreeeLe()))
                .toList());
        return stockees;
    }

    @Test
    @DisplayName("une paire RSA est créée au premier démarrage")
    void creeUneCleSiAucune() {
        List<CleSignature> stockees = enBase();
        GestionnaireCles gestionnaire = new GestionnaireCles(depot);

        gestionnaire.assurerUneCleActive();

        assertEquals(1, stockees.size());
        CleSignature cle = stockees.get(0);
        assertNotNull(cle.getKid());
        assertFalse(cle.getClePublique().isBlank());
        assertFalse(cle.getClePrivee().isBlank());
        assertTrue(cle.estActive());
    }

    @Test
    @DisplayName("un redémarrage réutilise la clé en base plutôt que d'en créer une autre")
    void redemarrageNeRegenerePas() {
        enBase();
        GestionnaireCles gestionnaire = new GestionnaireCles(depot);

        gestionnaire.assurerUneCleActive();
        String premierKid = gestionnaire.clesPubliables().get(0).getKeyID();
        gestionnaire.assurerUneCleActive();   // second démarrage

        assertEquals(1, gestionnaire.clesPubliables().size(),
                "regénérer invaliderait tous les jetons de rafraîchissement en cours");
        assertEquals(premierKid, gestionnaire.clesPubliables().get(0).getKeyID());
    }

    @Test
    @DisplayName("la clé publiée porte bien une clé privée côté serveur, et un kid")
    void cleUtilisablePourSigner() {
        enBase();
        GestionnaireCles gestionnaire = new GestionnaireCles(depot);
        gestionnaire.assurerUneCleActive();

        JWK jwk = gestionnaire.clesPubliables().get(0);
        assertInstanceOf(RSAKey.class, jwk);
        RSAKey rsa = (RSAKey) jwk;
        assertNotNull(rsa.getKeyID());
        assertTrue(rsa.isPrivate(), "le serveur doit pouvoir signer");
        // Ce qui part au JWKS est la vue publique : c'est elle qui ne doit
        // jamais contenir la clé privée.
        assertFalse(rsa.toPublicJWK().isPrivate(),
                "le JWKS ne doit publier que la clé publique, sinon n'importe qui peut forger");
    }

    @Test
    @DisplayName("la rotation crée une clé et garde l'ancienne vérifiable")
    void rotationAvecRecouvrement() {
        enBase();
        GestionnaireCles gestionnaire = new GestionnaireCles(depot);
        gestionnaire.assurerUneCleActive();
        String ancienKid = gestionnaire.clesPubliables().get(0).getKeyID();

        CleSignature nouvelle = gestionnaire.tourner();

        List<String> publiees = gestionnaire.clesPubliables().stream().map(JWK::getKeyID).toList();
        assertEquals(2, publiees.size(),
                "sans recouvrement, la rotation invaliderait tous les jetons en circulation");
        assertEquals(nouvelle.getKid(), publiees.get(0),
                "la clé active doit venir en tête : c'est elle qui signe");
        assertTrue(publiees.contains(ancienKid));
        assertNotEquals(ancienKid, nouvelle.getKid());
    }

    @Test
    @DisplayName("après rotation, une seule clé est désignée pour signer")
    void uneSeuleCleSigneApresRotation() {
        // Le défaut que ce test verrouille n'apparaissait pas à la mise en
        // service mais à la PREMIÈRE ROTATION : deux clés étant publiées,
        // l'encodeur refusait de signer (« multiple keys for the signing
        // algorithm ») et toute émission de jeton s'arrêtait.
        enBase();
        GestionnaireCles gestionnaire = new GestionnaireCles(depot);
        gestionnaire.assurerUneCleActive();
        String premier = gestionnaire.kidActif();

        CleSignature nouvelle = gestionnaire.tourner();

        assertEquals(2, gestionnaire.clesPubliables().size(), "deux clés restent vérifiables");
        assertEquals(nouvelle.getKid(), gestionnaire.kidActif(),
                "une seule doit signer, et c'est la nouvelle");
        assertNotEquals(premier, gestionnaire.kidActif());

        long actives = gestionnaire.clesPubliables().stream()
                .map(JWK::getKeyID)
                .filter(kid -> kid.equals(gestionnaire.kidActif()))
                .count();
        assertEquals(1, actives, "le sélecteur de signature doit trouver exactement une clé");
    }

    @Test
    @DisplayName("sans aucune clé, le kid actif est nul plutôt qu'une exception")
    void kidActifNulSiAucuneCle() {
        enBase();
        assertNull(new GestionnaireCles(depot).kidActif());
    }

    @Test
    @DisplayName("une clé dont le recouvrement est écoulé cesse d'être publiée")
    void cleExpireeCesseDEtrePubliee() {
        CleSignature perimee = CleSignature.builder()
                .kid("perimee")
                .clePublique("x").clePrivee("y")
                .creeeLe(LocalDateTime.now().minusMonths(6))
                .retireeLe(LocalDateTime.now().minusDays(1))
                .build();
        assertFalse(perimee.estPubliable());
        assertFalse(perimee.estActive());

        CleSignature enRecouvrement = CleSignature.builder()
                .kid("recouvrement")
                .clePublique("x").clePrivee("y")
                .creeeLe(LocalDateTime.now().minusDays(2))
                .retireeLe(LocalDateTime.now().plusDays(5))
                .build();
        assertTrue(enRecouvrement.estPubliable());
        assertFalse(enRecouvrement.estActive(),
                "une clé retirée ne doit plus signer, seulement rester vérifiable");
    }
}
