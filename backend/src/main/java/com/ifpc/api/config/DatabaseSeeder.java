package com.ifpc.api.config;

import com.ifpc.api.models.Role;
import com.ifpc.api.models.User;
import com.ifpc.api.repositories.UserRepository;
import com.ifpc.api.security.federation.AmorceClients;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class DatabaseSeeder {

    @Bean
    public CommandLineRunner seedDatabase(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate,
            AmorceClients amorceClients
    ) {
        return args -> {
            repairCuvesSchema(jdbcTemplate);
            repairLotsSchema(jdbcTemplate);
            repairTenantSchema(jdbcTemplate);
            // Avant toute utilisation de UserRepository : la colonne
            // « external_id » fait désormais partie de l'entité, donc de chaque
            // SELECT que JPA émet. Tant qu'elle n'existe pas en base, la
            // moindre lecture échoue.
            repairFederationSchema(jdbcTemplate);

            // Création de l'admin par défaut s'il n'existe pas.
            // Le mot de passe n'est JAMAIS écrit dans le dépôt : il vient de
            // PADOC_ADMIN_PASSWORD, ou il est tiré au hasard et affiché une
            // seule fois dans le journal de démarrage.
            if (userRepository.findByEmail(adminEmail()).isEmpty()) {
                String motDePasse = motDePasseAdminInitial();
                User admin = User.builder()
                        .firstName("Super")
                        .lastName("Admin")
                        .email(adminEmail())
                        .password(passwordEncoder.encode(motDePasse))
                        .role(Role.ADMIN)
                        .enabled(true)
                        .build();
                userRepository.save(admin);

                System.out.println("====== COMPTE ADMIN CRÉÉ ======");
                System.out.println("Email : " + adminEmail());
                if (System.getenv("PADOC_ADMIN_PASSWORD") != null) {
                    System.out.println("Mot de passe : celui fourni via PADOC_ADMIN_PASSWORD");
                } else {
                    System.out.println("Mot de passe (affiché une seule fois) : " + motDePasse);
                    System.out.println("Le noter maintenant, puis le changer à la première connexion.");
                }
                System.out.println("===============================");
            }

            amorceClients.amorcer();
        };
    }

    // ── Fédération d'identité ────────────────────────────────────────────────
    /**
     * Donne un identifiant externe aux comptes qui préexistent à la fédération.
     *
     * <p>Hibernate ne peut pas ajouter seul cette colonne : elle est
     * {@code NOT NULL} et la table porte déjà des lignes, donc l'{@code ALTER}
     * qu'il tenterait échouerait. On procède dans l'ordre qui marche — colonne
     * facultative, remplissage, puis contraintes.</p>
     *
     * <p>C'est l'étape la plus irréversible de la fédération : dès qu'une
     * plateforme cliente a rattaché ses comptes locaux à un {@code sub}, ces
     * valeurs ne peuvent plus changer sans casser ses rattachements
     * (spec fédération §7.1). D'où le {@code WHERE external_id IS NULL} : un
     * identifiant déjà attribué n'est jamais réécrit, même si cette méthode
     * est rejouée.</p>
     */
    private void repairFederationSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS external_id varchar(36)");

        // gen_random_uuid() est fourni en standard depuis PostgreSQL 13.
        jdbcTemplate.execute(
                "UPDATE users SET external_id = gen_random_uuid()::text WHERE external_id IS NULL");

        // Unicité avant NOT NULL : si un doublon existait, mieux vaut échouer
        // ici, sur une base encore sans client fédéré, que plus tard.
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS uk_users_external_id ON users (external_id)");
        jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN external_id SET NOT NULL");
    }

    // ── Cloisonnement multi-locataire ────────────────────────────────────────
    // Ajoute la colonne « owner_email » sur les lots et les cuves, rattache les
    // données existantes à leur utilisateur d'origine (déduit du journal des
    // opérations, puis des stockages), et bascule l'unicité de l'identifiant de
    // lot du global vers le périmètre d'un propriétaire.
    private void repairTenantSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("ALTER TABLE lots  ADD COLUMN IF NOT EXISTS owner_email varchar(255)");
        jdbcTemplate.execute("ALTER TABLE cuves ADD COLUMN IF NOT EXISTS owner_email varchar(255)");

        // 1. Lots : l'auteur de la première opération qui les mentionne
        jdbcTemplate.execute("""
                UPDATE lots l SET owner_email = sub.user_email
                FROM (
                    SELECT DISTINCT ON (lot_id) lot_id, user_email
                    FROM operations
                    WHERE lot_id IS NOT NULL AND user_email IS NOT NULL
                    ORDER BY lot_id, created_at
                ) sub
                WHERE l.id = sub.lot_id AND l.owner_email IS NULL
                """);
        jdbcTemplate.execute("""
                UPDATE lots l SET owner_email = sub.user_email
                FROM (
                    SELECT DISTINCT ON (lot_resultat_id) lot_resultat_id, user_email
                    FROM operations
                    WHERE lot_resultat_id IS NOT NULL AND user_email IS NOT NULL
                    ORDER BY lot_resultat_id, created_at
                ) sub
                WHERE l.id = sub.lot_resultat_id AND l.owner_email IS NULL
                """);

        // 2. Cuves : idem, source ou destination
        jdbcTemplate.execute("""
                UPDATE cuves c SET owner_email = sub.user_email
                FROM (
                    SELECT DISTINCT ON (cuve_id) cuve_id, user_email
                    FROM (
                        SELECT cuve_source_id AS cuve_id, user_email, created_at FROM operations
                         WHERE cuve_source_id IS NOT NULL AND user_email IS NOT NULL
                        UNION ALL
                        SELECT cuve_dest_id AS cuve_id, user_email, created_at FROM operations
                         WHERE cuve_dest_id IS NOT NULL AND user_email IS NOT NULL
                    ) x
                    ORDER BY cuve_id, created_at
                ) sub
                WHERE c.id = sub.cuve_id AND c.owner_email IS NULL
                """);

        // 3. Ce que le journal ne couvre pas : propager via les stockages
        jdbcTemplate.execute("""
                UPDATE cuves c SET owner_email = sub.owner_email
                FROM (
                    SELECT DISTINCT ON (s.cuve_id) s.cuve_id, l.owner_email
                    FROM stockages s JOIN lots l ON l.id = s.lot_id
                    WHERE l.owner_email IS NOT NULL
                    ORDER BY s.cuve_id, s.date_debut
                ) sub
                WHERE c.id = sub.cuve_id AND c.owner_email IS NULL
                """);
        jdbcTemplate.execute("""
                UPDATE lots l SET owner_email = sub.owner_email
                FROM (
                    SELECT DISTINCT ON (s.lot_id) s.lot_id, c.owner_email
                    FROM stockages s JOIN cuves c ON c.id = s.cuve_id
                    WHERE c.owner_email IS NOT NULL
                    ORDER BY s.lot_id, s.date_debut
                ) sub
                WHERE l.id = sub.lot_id AND l.owner_email IS NULL
                """);

        // 4. Reliquat non attribuable : rattaché au compte de repli, jamais
        //    laissé sans propriétaire (une ligne orpheline serait invisible).
        String legacyOwner = legacyOwnerEmail();
        jdbcTemplate.update("UPDATE lots  SET owner_email = ? WHERE owner_email IS NULL", legacyOwner);
        jdbcTemplate.update("UPDATE cuves SET owner_email = ? WHERE owner_email IS NULL", legacyOwner);

        // 5. Opérations sans auteur : héritent du propriétaire de leur objet
        jdbcTemplate.execute("""
                UPDATE operations o SET user_email = COALESCE(
                    (SELECT owner_email FROM lots  WHERE id = o.lot_id),
                    (SELECT owner_email FROM lots  WHERE id = o.lot_resultat_id),
                    (SELECT owner_email FROM cuves WHERE id = o.cuve_source_id),
                    (SELECT owner_email FROM cuves WHERE id = o.cuve_dest_id)
                )
                WHERE o.user_email IS NULL
                """);

        // 6. L'identifiant de lot n'est plus unique globalement mais par
        //    propriétaire — et seulement parmi les lots vivants, pour qu'un
        //    identifiant supprimé puisse être réutilisé.
        jdbcTemplate.execute("""
                DO $$
                DECLARE contrainte record;
                BEGIN
                    FOR contrainte IN
                        SELECT con.conname
                        FROM pg_constraint con
                        JOIN pg_class rel ON rel.oid = con.conrelid
                        JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = con.conkey[1]
                        WHERE rel.relname = 'lots'
                          AND con.contype = 'u'
                          AND array_length(con.conkey, 1) = 1
                          AND att.attname = 'identifiant'
                    LOOP
                        EXECUTE 'ALTER TABLE lots DROP CONSTRAINT ' || quote_ident(contrainte.conname);
                    END LOOP;
                END $$;
                """);
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_lots_owner_identifiant
                ON lots (owner_email, identifiant)
                WHERE deleted = false
                """);
    }

    /** Adresse du compte administrateur initial. */
    private String adminEmail() {
        String configured = System.getenv("PADOC_ADMIN_EMAIL");
        return configured != null && !configured.isBlank() ? configured.trim() : "admin@ifpc.com";
    }

    /**
     * Mot de passe du compte administrateur initial : celui de
     * PADOC_ADMIN_PASSWORD s'il est fourni, sinon une valeur aléatoire.
     * Aucun mot de passe en clair ne doit vivre dans le code source.
     */
    private String motDePasseAdminInitial() {
        String configured = System.getenv("PADOC_ADMIN_PASSWORD");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        byte[] aleatoire = new byte[24];
        new java.security.SecureRandom().nextBytes(aleatoire);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aleatoire);
    }

    /** Compte qui hérite des données antérieures au cloisonnement. */
    private String legacyOwnerEmail() {
        String configured = System.getenv("PADOC_LEGACY_OWNER_EMAIL");
        return configured != null && !configured.isBlank() ? configured.trim() : adminEmail();
    }

    private void repairCuvesSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("ALTER TABLE cuves ADD COLUMN IF NOT EXISTS volume_max double precision");
        jdbcTemplate.execute("ALTER TABLE cuves ADD COLUMN IF NOT EXISTS updated_at timestamp(6)");
        jdbcTemplate.execute("ALTER TABLE cuves ADD COLUMN IF NOT EXISTS created_at timestamp(6)");
        jdbcTemplate.execute("ALTER TABLE cuves ADD COLUMN IF NOT EXISTS deleted boolean");
        jdbcTemplate.execute("ALTER TABLE cuves ADD COLUMN IF NOT EXISTS deleted_at timestamp(6)");
        jdbcTemplate.execute("ALTER TABLE cuves ADD COLUMN IF NOT EXISTS statut_physique varchar(30)");
        jdbcTemplate.execute("ALTER TABLE cuves ADD COLUMN IF NOT EXISTS plan_x double precision");
        jdbcTemplate.execute("ALTER TABLE cuves ADD COLUMN IF NOT EXISTS plan_y double precision");

        jdbcTemplate.execute("""
                DO $$
                BEGIN
                    IF EXISTS (
                        SELECT 1
                        FROM information_schema.columns
                        WHERE table_name = 'cuves'
                          AND column_name = 'volume_actuel'
                    ) THEN
                        ALTER TABLE cuves ALTER COLUMN volume_actuel SET DEFAULT 0;
                        UPDATE cuves SET volume_actuel = 0 WHERE volume_actuel IS NULL;
                    END IF;
                END $$;
                """);
        jdbcTemplate.execute("UPDATE cuves SET volume_max = COALESCE(volume_max, 20000) WHERE volume_max IS NULL");
        jdbcTemplate.execute("UPDATE cuves SET updated_at = now() WHERE updated_at IS NULL");
        jdbcTemplate.execute("UPDATE cuves SET created_at = updated_at WHERE created_at IS NULL");
        jdbcTemplate.execute("UPDATE cuves SET deleted = false WHERE deleted IS NULL");
        jdbcTemplate.execute("UPDATE cuves SET statut_physique = 'PROPRE' WHERE statut_physique IS NULL");

        jdbcTemplate.execute("ALTER TABLE cuves ALTER COLUMN volume_max SET NOT NULL");
        jdbcTemplate.execute("ALTER TABLE cuves ALTER COLUMN updated_at SET NOT NULL");
        jdbcTemplate.execute("ALTER TABLE cuves ALTER COLUMN created_at SET NOT NULL");
        jdbcTemplate.execute("ALTER TABLE cuves ALTER COLUMN deleted SET DEFAULT false");
        jdbcTemplate.execute("ALTER TABLE cuves ALTER COLUMN deleted SET NOT NULL");
        jdbcTemplate.execute("ALTER TABLE cuves ALTER COLUMN statut_physique SET DEFAULT 'PROPRE'");
        jdbcTemplate.execute("ALTER TABLE cuves ALTER COLUMN statut_physique SET NOT NULL");
    }

    private void repairLotsSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("ALTER TABLE lots ADD COLUMN IF NOT EXISTS deleted boolean");
        jdbcTemplate.execute("ALTER TABLE lots ADD COLUMN IF NOT EXISTS deleted_at timestamp(6)");
        jdbcTemplate.execute("UPDATE lots SET deleted = false WHERE deleted IS NULL");
        jdbcTemplate.execute("ALTER TABLE lots ALTER COLUMN deleted SET DEFAULT false");
        jdbcTemplate.execute("ALTER TABLE lots ALTER COLUMN deleted SET NOT NULL");
    }
}
