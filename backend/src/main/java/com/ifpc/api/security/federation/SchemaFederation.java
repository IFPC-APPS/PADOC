package com.ifpc.api.security.federation;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Crée les tables de Spring Authorization Server.
 *
 * <p>Hibernate ne peut pas s'en charger : ces tables ne sont pas des entités
 * JPA, elles sont lues et écrites directement par les dépôts JDBC du serveur
 * d'autorisation. {@code ddl-auto: update} les ignore donc.</p>
 *
 * <p>Le schéma est celui livré dans le jar
 * ({@code oauth2-registered-client-schema.sql} et ses voisins), avec les deux
 * adaptations que le fichier amont demande explicitement pour PostgreSQL :
 * {@code blob} devient {@code text} — PostgreSQL n'a pas de type {@code blob} —
 * et {@code timestamp} devient {@code timestamptz}, sans quoi les instants sont
 * stockés sans fuseau et se décalent.</p>
 *
 * <p><b>Pourquoi à l'initialisation du bean, et non dans le {@code CommandLineRunner}.</b>
 * {@code JdbcOAuth2AuthorizationService} lit les <em>métadonnées des colonnes</em>
 * dans son constructeur, pour décider du type SQL de chaque paramètre qu'il
 * écrira. Si les tables n'existent pas encore à ce moment-là, il retombe sur ses
 * valeurs par défaut — il écrit alors des tableaux d'octets dans des colonnes
 * {@code text}, qui s'y enregistrent sous la forme {@code [B@77c4b007}, et toute
 * relecture échoue.</p>
 *
 * <p>Le défaut ne se voit qu'au <b>premier</b> démarrage sur une base neuve :
 * les fois suivantes les tables préexistent et tout fonctionne. Autrement dit,
 * il ne se manifeste qu'au déploiement initial — exactement là où il coûte le
 * plus cher. D'où le {@code @PostConstruct} : les tables existent avant que le
 * moindre dépôt JDBC du serveur d'autorisation ne soit construit.</p>
 *
 * <p>{@code CREATE TABLE IF NOT EXISTS} plutôt qu'une migration versionnée :
 * c'est le geste déjà retenu ailleurs dans ce module ({@code DatabaseSeeder}),
 * et il rend le démarrage idempotent.</p>
 */
@Component
@RequiredArgsConstructor
public class SchemaFederation {

    private final JdbcTemplate jdbc;

    @PostConstruct
    public void creer() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS oauth2_registered_client (
                    id varchar(100) NOT NULL,
                    client_id varchar(100) NOT NULL,
                    client_id_issued_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    client_secret varchar(200) DEFAULT NULL,
                    client_secret_expires_at timestamptz DEFAULT NULL,
                    client_name varchar(200) NOT NULL,
                    client_authentication_methods varchar(1000) NOT NULL,
                    authorization_grant_types varchar(1000) NOT NULL,
                    redirect_uris varchar(1000) DEFAULT NULL,
                    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
                    scopes varchar(1000) NOT NULL,
                    client_settings varchar(2000) NOT NULL,
                    token_settings varchar(2000) NOT NULL,
                    PRIMARY KEY (id)
                )""");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS oauth2_authorization (
                    id varchar(100) NOT NULL,
                    registered_client_id varchar(100) NOT NULL,
                    principal_name varchar(200) NOT NULL,
                    authorization_grant_type varchar(100) NOT NULL,
                    authorized_scopes varchar(1000) DEFAULT NULL,
                    attributes text DEFAULT NULL,
                    state varchar(500) DEFAULT NULL,
                    authorization_code_value text DEFAULT NULL,
                    authorization_code_issued_at timestamptz DEFAULT NULL,
                    authorization_code_expires_at timestamptz DEFAULT NULL,
                    authorization_code_metadata text DEFAULT NULL,
                    access_token_value text DEFAULT NULL,
                    access_token_issued_at timestamptz DEFAULT NULL,
                    access_token_expires_at timestamptz DEFAULT NULL,
                    access_token_metadata text DEFAULT NULL,
                    access_token_type varchar(100) DEFAULT NULL,
                    access_token_scopes varchar(1000) DEFAULT NULL,
                    oidc_id_token_value text DEFAULT NULL,
                    oidc_id_token_issued_at timestamptz DEFAULT NULL,
                    oidc_id_token_expires_at timestamptz DEFAULT NULL,
                    oidc_id_token_metadata text DEFAULT NULL,
                    refresh_token_value text DEFAULT NULL,
                    refresh_token_issued_at timestamptz DEFAULT NULL,
                    refresh_token_expires_at timestamptz DEFAULT NULL,
                    refresh_token_metadata text DEFAULT NULL,
                    user_code_value text DEFAULT NULL,
                    user_code_issued_at timestamptz DEFAULT NULL,
                    user_code_expires_at timestamptz DEFAULT NULL,
                    user_code_metadata text DEFAULT NULL,
                    device_code_value text DEFAULT NULL,
                    device_code_issued_at timestamptz DEFAULT NULL,
                    device_code_expires_at timestamptz DEFAULT NULL,
                    device_code_metadata text DEFAULT NULL,
                    PRIMARY KEY (id)
                )""");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS oauth2_authorization_consent (
                    registered_client_id varchar(100) NOT NULL,
                    principal_name varchar(200) NOT NULL,
                    authorities varchar(1000) NOT NULL,
                    PRIMARY KEY (registered_client_id, principal_name)
                )""");
    }
}
