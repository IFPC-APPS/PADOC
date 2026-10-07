package com.ifpc.api.services;

import com.ifpc.api.models.Fonctionnalite;
import com.ifpc.api.repositories.FonctionnaliteRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecture et écriture des fonctionnalités ouvertes au public.
 *
 * <p>Le catalogue est déclaré ici, dans le code : une fonctionnalité existe
 * parce qu'il y a des écrans et des routes derrière elle, pas parce que
 * quelqu'un a inséré une ligne en base. L'administrateur choisit de l'ouvrir
 * ou de la fermer, il n'en invente pas.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceFonctionnalites {

    /** Clé → libellé et effet de la fermeture, dans l'ordre d'affichage. */
    public static final Map<String, String[]> CATALOGUE = new LinkedHashMap<>();

    static {
        CATALOGUE.put("pasteurisation", new String[]{
                "Pasteurisation",
                "Calcul de valeur pasteurisatrice et aide au barème."});
        CATALOGUE.put("colorimetrie", new String[]{
                "Colorimétrie",
                "Mesure de couleur et assemblage."});
        CATALOGUE.put("cuves", new String[]{
                "Gestion de cuve",
                "Chai virtuel, suivi des cuves, lots et opérations."});
        CATALOGUE.put("assistant", new String[]{
                "Assistant AsCoCid",
                "Questions au Livre de Connaissances."});
        CATALOGUE.put("historique", new String[]{
                "Historique",
                "Registre des calculs et des opérations enregistrés."});
    }

    private final FonctionnaliteRepository depot;

    /**
     * Inscrit les fonctionnalités que la base ne connaît pas encore.
     *
     * <p>Seulement celles-là. Réécrire les lignes existantes à chaque
     * démarrage rallumerait ce qu'un administrateur vient d'éteindre, et le
     * déploiement suivant déferait silencieusement sa décision — exactement le
     * genre de panne que personne ne rattache à sa cause.</p>
     *
     * <p>Une nouvelle fonctionnalité arrive <b>ouverte</b> : le code qui la
     * sert vient d'être déployé, la fermer d'office surprendrait.</p>
     */
    @PostConstruct
    public void amorcer() {
        CATALOGUE.forEach((cle, texte) -> {
            if (depot.existsById(cle)) {
                return;
            }
            depot.save(Fonctionnalite.builder()
                    .cle(cle)
                    .libelle(texte[0])
                    .description(texte[1])
                    .activee(true)
                    .build());
            log.info("Fonctionnalité « {} » inscrite, ouverte par défaut.", cle);
        });
    }

    /** Toutes les fonctionnalités, dans l'ordre du catalogue. */
    public List<Fonctionnalite> lister() {
        return CATALOGUE.keySet().stream()
                .map(cle -> depot.findById(cle).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * État vu par un utilisateur : clé → ouverte ou non.
     *
     * <p>Un administrateur voit tout ouvert. C'est la contrepartie du réglage
     * global : il doit pouvoir préparer et vérifier une fonctionnalité que le
     * public ne voit pas encore.</p>
     */
    public Map<String, Boolean> etatPour(boolean administrateur) {
        Map<String, Boolean> etat = new LinkedHashMap<>();
        for (String cle : CATALOGUE.keySet()) {
            boolean ouverte = administrateur
                    || depot.findById(cle).map(Fonctionnalite::getActivee).orElse(true);
            etat.put(cle, ouverte);
        }
        return etat;
    }

    /**
     * Une fonctionnalité est-elle accessible à cet utilisateur ?
     *
     * <p>Une clé inconnue rend {@code true}. Le contrôle ne doit jamais fermer
     * un écran par accident, par exemple après le renommage d'une clé : une
     * fonctionnalité qui disparaît sans décision est plus grave qu'une
     * fonctionnalité qui reste ouverte.</p>
     */
    public boolean estAccessible(String cle, boolean administrateur) {
        if (administrateur || !CATALOGUE.containsKey(cle)) {
            return true;
        }
        return depot.findById(cle).map(Fonctionnalite::getActivee).orElse(true);
    }

    /** Ouvre ou ferme une fonctionnalité. Rend l'état enregistré. */
    public Fonctionnalite basculer(String cle, boolean activee, String parQui) {
        if (!CATALOGUE.containsKey(cle)) {
            throw new IllegalArgumentException("fonctionnalité inconnue : " + cle);
        }
        Fonctionnalite f = depot.findById(cle).orElseGet(() -> Fonctionnalite.builder()
                .cle(cle)
                .libelle(CATALOGUE.get(cle)[0])
                .description(CATALOGUE.get(cle)[1])
                .build());
        f.setActivee(activee);
        f.setModifieLe(LocalDateTime.now());
        f.setModifiePar(parQui);
        log.info("Fonctionnalité « {} » {} par {}.", cle, activee ? "ouverte" : "fermée", parQui);
        return depot.save(f);
    }
}
