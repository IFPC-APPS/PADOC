package com.ifpc.api.controllers;

import com.ifpc.api.models.AuditAction;
import com.ifpc.api.models.HabilitationPlateforme;
import com.ifpc.api.models.User;
import com.ifpc.api.repositories.HabilitationPlateformeRepository;
import com.ifpc.api.repositories.UserRepository;
import com.ifpc.api.security.Tenant;
import com.ifpc.api.security.federation.GestionnaireCles;
import com.ifpc.api.services.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Administration de la fédération : qui a accès à quelle plateforme.
 *
 * <p>Les méthodes sont transactionnelles parce que {@code open-in-view} est
 * désactivé — délibérément — et que le rattachement à l'utilisateur est chargé
 * paresseusement : construire la réponse hors transaction lève une
 * {@code LazyInitializationException}.</p>
 *
 * <p>Accorder un accès est un acte explicite et journalisé. Un compte IFPC
 * n'ouvre pas silencieusement l'accès à tout outil qui rejoint la fédération
 * (spec fédération §7.2) : sans habilitation, le parcours d'autorisation est
 * refusé avant l'émission du moindre code.</p>
 */
@RestController
@RequestMapping("/api/admin/federation")
@RequiredArgsConstructor
public class FederationAdminController {

    private final UserRepository utilisateurs;
    private final HabilitationPlateformeRepository habilitations;
    private final GestionnaireCles gestionnaireCles;
    private final AuditService audit;

    /** Les habilitations accordées sur une plateforme. */
    @GetMapping("/habilitations")
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<HabilitationDto>> lister(@RequestParam("clientId") String clientId) {
        List<HabilitationDto> resultat = habilitations.findByClientId(clientId).stream()
                .map(FederationAdminController::versDto)
                .toList();
        return ResponseEntity.ok(resultat);
    }

    /** Les habilitations d'un utilisateur, toutes plateformes confondues. */
    @GetMapping("/utilisateurs/{utilisateurId}/habilitations")
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<HabilitationDto>> listerPourUtilisateur(@PathVariable Long utilisateurId) {
        List<HabilitationDto> resultat = habilitations.findByUtilisateurId(utilisateurId).stream()
                .map(FederationAdminController::versDto)
                .toList();
        return ResponseEntity.ok(resultat);
    }

    /**
     * Accorde ou met à jour l'accès d'un utilisateur à une plateforme.
     *
     * <p>Les rôles transmis sont ceux de la plateforme cliente, pas ceux
     * d'IFPC : ils ne sont donc pas validés contre un enum — le vocabulaire
     * appartient à l'outil fédéré.</p>
     */
    @PutMapping("/utilisateurs/{utilisateurId}/habilitations/{clientId}")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<HabilitationDto> accorder(
            @PathVariable Long utilisateurId,
            @PathVariable String clientId,
            @RequestBody(required = false) HabilitationRequest requete) {

        User utilisateur = utilisateurs.findById(utilisateurId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));

        String roles = requete == null || requete.roles() == null ? "" : requete.roles().trim();

        HabilitationPlateforme habilitation = habilitations
                .findByUtilisateurIdAndClientId(utilisateurId, clientId)
                .orElseGet(() -> HabilitationPlateforme.builder()
                        .utilisateur(utilisateur)
                        .clientId(clientId)
                        .accordeLe(LocalDateTime.now())
                        .build());

        habilitation.setRoles(roles);
        habilitation.setAccordePar(Tenant.currentEmail());
        habilitations.save(habilitation);

        audit.consigner(AuditAction.HABILITATION_ACCORDEE, "utilisateur", utilisateur.getEmail(),
                Map.of("plateforme", clientId, "roles", roles.isEmpty() ? "(aucun)" : roles));

        return ResponseEntity.ok(versDto(habilitation));
    }

    /**
     * Retire l'accès d'un utilisateur à une plateforme.
     *
     * <p>Effet différé de la durée du jeton d'accès — dix minutes au plus. Pour
     * couper immédiatement, révoquer aussi le jeton de rafraîchissement via
     * {@code /oauth2/revoke} (spec §10).</p>
     */
    @DeleteMapping("/utilisateurs/{utilisateurId}/habilitations/{clientId}")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> retirer(@PathVariable Long utilisateurId, @PathVariable String clientId) {
        HabilitationPlateforme habilitation = habilitations
                .findByUtilisateurIdAndClientId(utilisateurId, clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Habilitation introuvable"));

        String email = habilitation.getUtilisateur().getEmail();
        habilitations.delete(habilitation);

        audit.consigner(AuditAction.HABILITATION_RETIREE, "utilisateur", email,
                Map.of("plateforme", clientId));

        return ResponseEntity.noContent().build();
    }

    /**
     * Renouvelle la clé de signature des jetons fédérés.
     *
     * <p>L'ancienne reste publiée au JWKS quelques jours : les jetons déjà émis
     * continuent d'être vérifiables le temps d'expirer, et les caches des
     * plateformes clientes ont le temps de se rafraîchir.</p>
     */
    @PostMapping("/cles/rotation")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> tournerLesCles() {
        String kid = gestionnaireCles.tourner().getKid();
        audit.consigner(AuditAction.CLE_FEDERATION_TOURNEE, "federation", kid, Map.of());
        return ResponseEntity.ok(Map.of(
                "kid", kid,
                "message", "Nouvelle clé active. L'ancienne reste publiée le temps que les "
                        + "jetons en circulation expirent."));
    }

    private static HabilitationDto versDto(HabilitationPlateforme h) {
        User u = h.getUtilisateur();
        return new HabilitationDto(h.getId(), u.getId(), u.getEmail(), u.getExternalId(),
                h.getClientId(), h.rolesEnSet().stream().toList(), h.getAccordeLe(), h.getAccordePar());
    }

    public record HabilitationRequest(String roles) {}

    public record HabilitationDto(Long id, Long utilisateurId, String email, String externalId,
                                  String clientId, List<String> roles,
                                  LocalDateTime accordeLe, String accordePar) {}
}
