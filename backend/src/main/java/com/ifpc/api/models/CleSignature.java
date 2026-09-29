package com.ifpc.api.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Paire de clés RSA servant à signer les jetons fédérés.
 *
 * <p><b>Pourquoi en base et non en mémoire.</b> Une clé tirée au démarrage
 * change à chaque redéploiement : tous les jetons de rafraîchissement en cours
 * deviennent invérifiables, et deux instances de l'application signeraient avec
 * des clés différentes — un jeton émis par l'une serait rejeté par l'autre. La
 * base est le seul endroit que toutes les instances partagent et qui survit aux
 * redémarrages.</p>
 *
 * <p><b>Pourquoi la clé privée n'est pas chiffrée.</b> Il faudrait un second
 * secret pour la déchiffrer, que l'application devrait détenir au même endroit
 * — on déplacerait le problème sans le résoudre. La base de données est déjà
 * dans la même frontière de confiance que l'application : qui la lit lit aussi
 * les empreintes de mots de passe. C'est le compromis que retiennent les
 * fournisseurs d'identité auto-hébergés.</p>
 *
 * <p><b>Rotation.</b> Plusieurs clés coexistent. La plus récente non retirée
 * signe ; toutes les non expirées restent publiées au JWKS, le temps que les
 * jetons émis avec l'ancienne expirent et que les caches des plateformes
 * clientes se rafraîchissent. Sans ce recouvrement, toute rotation déconnecte
 * tout le monde (spec fédération §9).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cles_signature")
public class CleSignature {

    /** Le {@code kid} publié au JWKS : c'est lui qui désigne la clé dans l'en-tête du jeton. */
    @Id
    @Column(name = "kid", nullable = false, length = 64)
    private String kid;

    /** Clé publique, encodage X.509 en base64. */
    @Column(name = "cle_publique", nullable = false, length = 4000)
    private String clePublique;

    /** Clé privée, encodage PKCS#8 en base64. */
    @Column(name = "cle_privee", nullable = false, length = 8000)
    private String clePrivee;

    @Column(name = "creee_le", nullable = false)
    @Builder.Default
    private LocalDateTime creeeLe = LocalDateTime.now();

    /**
     * Date après laquelle la clé cesse d'être publiée au JWKS.
     *
     * <p>{@code null} tant que la clé est active. Renseignée à la rotation :
     * la clé ne signe plus, mais reste vérifiable jusqu'à cette date.</p>
     */
    @Column(name = "retiree_le")
    private LocalDateTime retireeLe;

    /** Vrai si la clé peut encore servir à signer. */
    public boolean estActive() {
        return retireeLe == null;
    }

    /** Vrai si la clé doit encore être publiée pour vérification. */
    public boolean estPubliable() {
        return retireeLe == null || retireeLe.isAfter(LocalDateTime.now());
    }
}
