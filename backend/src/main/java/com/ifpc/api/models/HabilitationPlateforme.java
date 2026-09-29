package com.ifpc.api.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Droit d'accès d'un utilisateur à une plateforme fédérée, et ses rôles
 * <em>sur cette plateforme</em>.
 *
 * <p><b>Pourquoi cette table plutôt que le rôle global.</b> {@link Role} a un
 * sens strictement IFPC : {@code EXPERT} débloque les paramètres de barème
 * thermique s'écartant du référentiel scientifique. Sur une plateforme
 * d'analyse sensorielle, cela ne veut rien dire. Propager le rôle IFPC tel quel
 * reviendrait soit à accorder des privilèges dénués de sens, soit à laisser
 * chaque plateforme réinterpréter {@code ROLE_EXPERT} à sa façon — ce qui place
 * la décision d'autorisation hors de tout contrôle (spec fédération §7.2).</p>
 *
 * <p><b>Conséquence assumée :</b> l'accès à une plateforme est un acte
 * d'administration explicite. Un compte IFPC n'ouvre pas silencieusement
 * l'accès à tout outil qui rejoint la fédération.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "habilitations_plateforme",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_habilitation_utilisateur_client",
                columnNames = {"utilisateur_id", "client_id"})
)
public class HabilitationPlateforme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false)
    private User utilisateur;

    /** {@code client_id} OAuth de la plateforme concernée. */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /**
     * Rôles applicables sur cette plateforme, séparés par des virgules.
     *
     * <p>Volontairement du texte libre et non un enum : ces rôles appartiennent
     * au vocabulaire de la plateforme cliente, pas au nôtre. Les figer dans un
     * enum IFPC imposerait de redéployer le fournisseur d'identité chaque fois
     * qu'un outil fédéré invente un rôle.</p>
     */
    @Column(name = "roles", nullable = false, length = 500)
    @Builder.Default
    private String roles = "";

    @Column(name = "accorde_le", nullable = false)
    @Builder.Default
    private LocalDateTime accordeLe = LocalDateTime.now();

    /** Adresse de l'administrateur qui a accordé l'accès — pour l'audit. */
    @Column(name = "accorde_par", length = 255)
    private String accordePar;

    /** Les rôles sous forme de liste, vides écartés. */
    public Set<String> rolesEnSet() {
        if (roles == null || roles.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
