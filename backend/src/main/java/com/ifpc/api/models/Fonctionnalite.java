package com.ifpc.api.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Un pan de l'application que l'on peut ouvrir ou fermer sans toucher au code.
 *
 * <p>Pourquoi en base plutôt qu'en variable d'environnement : une variable
 * impose un redéploiement, donc une indisponibilité, et son changement ne
 * laisse aucune trace de qui l'a décidé ni quand. Ici un administrateur agit
 * depuis son écran, en direct, et la modification est datée.</p>
 *
 * <p>Le réglage est <b>global</b> : il vaut pour tous les comptes non
 * administrateurs. Les administrateurs continuent de tout voir — sans quoi ils
 * ne pourraient ni préparer ni vérifier une fonctionnalité fermée au public.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fonctionnalites")
public class Fonctionnalite {

    /**
     * Clé stable, connue du code du front comme de celui du back.
     *
     * <p>C'est elle l'identifiant, et non un entier auto-incrémenté : une
     * fonctionnalité est désignée par son nom dans les deux applications, et
     * un identifiant technique n'ajouterait qu'une indirection à maintenir.</p>
     */
    @Id
    @Column(name = "cle", length = 64, nullable = false)
    private String cle;

    /** Libellé lisible, affiché dans l'écran d'administration. */
    @Column(name = "libelle", length = 160, nullable = false)
    private String libelle;

    /** Ce que la fermeture retire concrètement à l'utilisateur. */
    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "activee", nullable = false)
    @Builder.Default
    private Boolean activee = true;

    @Column(name = "modifie_le")
    private LocalDateTime modifieLe;

    /** Adresse de l'administrateur auteur du dernier changement. */
    @Column(name = "modifie_par", length = 255)
    private String modifiePar;
}
