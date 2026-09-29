package com.ifpc.api.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Identifiant stable exposé à l'extérieur — le {@code sub} des jetons OIDC.
     *
     * <p>L'adresse e-mail ne peut pas tenir ce rôle : elle change au cours de la
     * vie d'un compte, et une plateforme fédérée qui y aurait rattaché ses
     * données locales les perdrait — ou pire, les rattacherait à un homonyme si
     * l'adresse est réattribuée. Cet identifiant, lui, ne change jamais.</p>
     *
     * <p>Il est tiré à la création et n'est jamais modifiable : une fois qu'une
     * plateforme cliente s'en sert comme clé, le changer casse ses
     * rattachements sans migration coordonnée (spec fédération §7.1).</p>
     *
     * <p>Le {@code DEFAULT} en base n'est pas décoratif : c'est lui qui permet
     * d'ajouter cette colonne {@code NOT NULL} à une table déjà peuplée.
     * PostgreSQL évalue {@code gen_random_uuid()} par ligne, chaque compte
     * existant reçoit donc un identifiant distinct au moment de l'{@code ALTER}.
     * Sans ce défaut, {@code ddl-auto: update} échoue et se contente de
     * journaliser — l'application démarrerait avec une colonne manquante (même
     * convention que les autres colonnes ajoutées après coup, cf.
     * {@code docs/schema.sql}).
     */
    @Column(name = "external_id", unique = true, nullable = false, updatable = false, length = 36,
            columnDefinition = "varchar(36) NOT NULL DEFAULT gen_random_uuid()::text")
    private String externalId;

    private String firstName;
    private String lastName;
    private String companyName;
    private String companyRole;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    private LocalDateTime lastLogin;

    private String resetPasswordToken;
    private LocalDateTime resetPasswordTokenExpiry;

    /**
     * Tire l'identifiant externe si l'appelant ne l'a pas fourni.
     *
     * <p>Placé ici plutôt que dans chaque appelant : un compte créé par
     * l'inscription, par l'amorçage ou par un test doit porter le même
     * invariant — {@code externalId} non nul, toujours.</p>
     */
    @PrePersist
    void attribuerIdentifiantExterne() {
        if (externalId == null || externalId.isBlank()) {
            externalId = java.util.UUID.randomUUID().toString();
        }
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * Un compte en attente de validation ne s'authentifie pas — quel que soit
     * le drapeau {@code enabled}.
     *
     * <p>Les deux pouvaient diverger : {@code AdminController.updateUserRole}
     * ne force {@code enabled} que lorsqu'il sort un compte de {@code PENDING},
     * si bien qu'un compte rétrogradé vers {@code PENDING} gardait
     * {@code enabled = true}. {@code AuthenticationService} testait les deux et
     * n'était donc pas touché, mais tout autre chemin d'authentification —
     * à commencer par le formulaire de connexion du parcours OAuth — ne
     * consulte que cette méthode. Le contrôle appartient donc ici, où aucun
     * chemin ne peut le contourner (spec fédération §8).</p>
     */
    @Override
    public boolean isEnabled() {
        return this.enabled && this.role != Role.PENDING;
    }
}
