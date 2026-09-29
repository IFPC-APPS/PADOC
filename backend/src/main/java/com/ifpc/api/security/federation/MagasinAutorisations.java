package com.ifpc.api.security.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifpc.api.models.User;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.function.Function;

/**
 * Le magasin des autorisations en cours, adapté à PostgreSQL.
 *
 * <p><b>Le défaut corrigé.</b> Le schéma amont déclare une dizaine de colonnes
 * en {@code blob} (les jetons, leurs métadonnées, les attributs de la
 * demande), et demande de les passer en {@code text} sous PostgreSQL, qui n'a
 * pas ce type. Mais la correspondance d'écriture, elle, continue d'annoncer ces
 * paramètres comme {@link Types#BLOB} : le pilote PostgreSQL écrit alors la
 * chaîne JSON sous sa forme hexadécimale — {@code \x7b224063...} au lieu de
 * {@code {"@class...}} — et la relecture échoue sur un JSON invalide.</p>
 *
 * <p>Le symptôme est déroutant : tout fonctionne jusqu'à l'écran de
 * consentement, puis la validation du consentement renvoie une erreur 500, car
 * c'est la première fois qu'une autorisation est <em>relue</em> après avoir été
 * écrite. L'échange de code suivant répond {@code invalid_grant}, ce qui laisse
 * croire à un problème de PKCE alors que le code n'a jamais pu être relu.</p>
 *
 * <p>On annonce donc ces paramètres en {@link Types#VARCHAR}, ce qui correspond
 * à la réalité de la colonne.</p>
 */
public class MagasinAutorisations extends JdbcOAuth2AuthorizationService {

    public MagasinAutorisations(JdbcOperations jdbc, RegisteredClientRepository clients) {
        super(jdbc, clients);

        ObjectMapper convertisseur = convertisseurJson();

        OAuth2AuthorizationRowMapper lecture = new OAuth2AuthorizationRowMapper(clients);
        lecture.setObjectMapper(convertisseur);
        setAuthorizationRowMapper(lecture);

        OAuth2AuthorizationParametersMapper ecriture = new OAuth2AuthorizationParametersMapper();
        ecriture.setObjectMapper(convertisseur);
        Function<OAuth2Authorization, List<SqlParameterValue>> parDefaut = ecriture;

        // Liste mutable obligatoire : à la mise à jour d'une autorisation, le
        // serveur retire lui-même des paramètres de cette liste pour composer
        // son UPDATE. Un List.of() ou un Stream.toList() y lève
        // UnsupportedOperationException, au moment précis de la validation du
        // consentement.
        setAuthorizationParametersMapper(autorisation -> parDefaut.apply(autorisation).stream()
                .map(MagasinAutorisations::enTexteSiBlob)
                .collect(Collectors.toCollection(ArrayList::new)));
    }

    /**
     * Le convertisseur JSON, augmenté du strict nécessaire.
     *
     * <p>Les modules de Spring Security savent relire ses propres types
     * ({@code Authentication}, autorités, jetons). Reste notre entité
     * {@code User}, qui est le principal : Jackson refuse une classe non
     * autorisée, et cette protection contre les chaînes de désérialisation
     * arbitraire ne doit pas être levée globalement. On déclare donc cette
     * classe seule, par un mixin ({@link UserMixin}).</p>
     */
    private static ObjectMapper convertisseurJson() {
        ObjectMapper convertisseur = new ObjectMapper();
        ClassLoader chargeur = MagasinAutorisations.class.getClassLoader();
        convertisseur.registerModules(SecurityJackson2Modules.getModules(chargeur));
        convertisseur.registerModule(new OAuth2AuthorizationServerJackson2Module());
        convertisseur.addMixIn(User.class, UserMixin.class);
        return convertisseur;
    }

    private static SqlParameterValue enTexteSiBlob(SqlParameterValue valeur) {
        return valeur.getSqlType() == Types.BLOB
                ? new SqlParameterValue(Types.VARCHAR, valeur.getValue())
                : valeur;
    }
}
