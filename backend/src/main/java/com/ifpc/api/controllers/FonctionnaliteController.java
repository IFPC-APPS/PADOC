package com.ifpc.api.controllers;

import com.ifpc.api.models.Fonctionnalite;
import com.ifpc.api.models.Role;
import com.ifpc.api.models.User;
import com.ifpc.api.services.ServiceFonctionnalites;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Les fonctionnalités ouvertes au public, en lecture pour tous et en écriture
 * pour les administrateurs.
 */
@RestController
@RequiredArgsConstructor
public class FonctionnaliteController {

    private final ServiceFonctionnalites service;

    /**
     * Ce que l'utilisateur courant a le droit de voir.
     *
     * <p>Ouvert sans authentification : la page de connexion et les écrans
     * publics ont besoin de cette réponse, et elle ne révèle rien — seulement
     * quels pans de l'outil sont proposés, ce que la navigation montre déjà.</p>
     */
    @GetMapping("/api/config/fonctionnalites")
    public ResponseEntity<Map<String, Boolean>> etat() {
        return ResponseEntity.ok(service.etatPour(estAdministrateur()));
    }

    @GetMapping("/api/admin/fonctionnalites")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<FonctionnaliteDto>> lister() {
        return ResponseEntity.ok(service.lister().stream().map(FonctionnaliteDto::de).toList());
    }

    @PutMapping("/api/admin/fonctionnalites/{cle}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> basculer(@PathVariable String cle,
                                      @RequestBody BasculeRequest requete) {
        if (requete == null || requete.activee() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "champ « activee » attendu"));
        }
        try {
            Fonctionnalite f = service.basculer(cle, requete.activee(), emailCourant());
            return ResponseEntity.ok(FonctionnaliteDto.de(f));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    private static User utilisateurCourant() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || !(a.getPrincipal() instanceof User u)) {
            return null;
        }
        return u;
    }

    private static boolean estAdministrateur() {
        User u = utilisateurCourant();
        return u != null && u.getRole() == Role.ADMIN;
    }

    private static String emailCourant() {
        User u = utilisateurCourant();
        return u == null ? "inconnu" : u.getEmail();
    }

    public record BasculeRequest(Boolean activee) {
    }

    public record FonctionnaliteDto(String cle, String libelle, String description,
                                    boolean activee, LocalDateTime modifieLe,
                                    String modifiePar) {
        static FonctionnaliteDto de(Fonctionnalite f) {
            return new FonctionnaliteDto(f.getCle(), f.getLibelle(), f.getDescription(),
                    Boolean.TRUE.equals(f.getActivee()), f.getModifieLe(), f.getModifiePar());
        }
    }
}
