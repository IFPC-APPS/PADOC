package com.ifpc.api.security.federation;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Autorise la relecture de {@link com.ifpc.api.models.User} dans une
 * autorisation OAuth persistée.
 *
 * <p><b>Pourquoi c'est nécessaire.</b> L'autorisation en cours enregistre
 * l'{@code Authentication} de l'utilisateur, donc notre entité {@code User},
 * qui est le principal produit par {@code DaoAuthenticationProvider}. Or
 * Jackson, tel que Spring Security le configure, refuse de désérialiser une
 * classe qui n'est pas explicitement autorisée — c'est une protection contre
 * les chaînes de désérialisation arbitraire, et il ne faut pas la désactiver
 * globalement. On déclare donc cette classe-ci, et elle seule.</p>
 *
 * <p><b>Le mot de passe est exclu.</b> Sans cela, l'empreinte BCrypt serait
 * recopiée dans {@code oauth2_authorization.attributes} à chaque parcours de
 * connexion — une seconde copie du secret, dans une table qui n'a aucune raison
 * de la porter. Rien n'en a besoin après l'authentification : la vérification
 * du mot de passe a déjà eu lieu, et le principal ne sert plus qu'à identifier
 * le porteur.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
@JsonDeserialize
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class UserMixin {

    @JsonIgnore
    private String password;
}
