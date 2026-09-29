-- =============================================================================
-- PADOC — Schéma relationnel
-- Base : PostgreSQL 15+
-- =============================================================================
--
-- CE FICHIER EST DESCRIPTIF, PAS EXÉCUTÉ.
--
-- Le schéma réel est produit par Hibernate (`spring.jpa.hibernate.ddl-auto:
-- update`) à partir des entités JPA de `com.ifpc.api.models`. Ce fichier en
-- donne la lecture SQL, pour qui doit comprendre ou auditer la base sans lire
-- le code Java.
--
-- Sa conformité aux entités est vérifiée par `SchemaDocumenteTest` : toute
-- table ou colonne ajoutée à une entité sans être reportée ici fait échouer
-- l'intégration continue. Le fichier avait dérivé d'une version entière du
-- modèle — il décrivait la base d'avant le cloisonnement multi-locataire.
--
-- Limite connue : `ddl-auto: update` n'enlève jamais rien et ne renomme rien.
-- Une colonne retirée d'une entité subsiste en base sans que rien ne le
-- signale. Passer à un outil de migration versionnée reste à décider.
--
-- Ajout de colonne sur une table peuplée : PostgreSQL refuse une colonne
-- NOT NULL sans DEFAULT si la table contient des lignes, et `ddl-auto: update`
-- se contente de journaliser l'échec — l'application démarre alors avec une
-- colonne manquante et toute requête qui la mentionne échoue. Les colonnes
-- NOT NULL ajoutées après coup portent donc un DEFAULT explicite dans
-- l'entité (`columnDefinition`).
--
-- Table orpheline : `product_config` a été retirée du modèle le 02/09/2026 (la
-- VP cible dérive du référentiel et n'est plus réglable). Ses données restent
-- en base, plus aucune application ne les lit. Elle peut être supprimée
-- manuellement une fois l'historique de ses valeurs jugé sans intérêt.
-- =============================================================================


-- ---------------------------------------------------------------------------
-- TABLE : users (comptes et droits)
-- ---------------------------------------------------------------------------
-- Le rôle PENDING est l'état d'un compte créé mais pas encore approuvé par un
-- administrateur ; il ne donne accès à rien.
--
-- external_id est le « sub » des jetons OpenID Connect émis aux plateformes
-- fédérées (docs/federation-identite.md §7.1). Il est tiré à la création et
-- n'est JAMAIS modifié : une plateforme cliente y rattache ses comptes locaux,
-- et le changer casserait ces rattachements sans migration coordonnée. L'e-mail
-- ne pouvait pas tenir ce rôle — il change au cours de la vie d'un compte.
--
-- Le DEFAULT permet d'ajouter cette colonne NOT NULL à une table déjà peuplée :
-- PostgreSQL évalue gen_random_uuid() par ligne, chaque compte existant reçoit
-- donc un identifiant distinct au moment de l'ALTER (cf. l'avertissement en
-- tête de ce fichier).

CREATE TABLE users (
    id                          BIGSERIAL       PRIMARY KEY,
    external_id                 VARCHAR(36)     NOT NULL UNIQUE DEFAULT gen_random_uuid()::text,
    first_name                  VARCHAR(255),
    last_name                   VARCHAR(255),
    company_name                VARCHAR(255),
    company_role                VARCHAR(255),
    email                       VARCHAR(255)    NOT NULL UNIQUE,
    password                    VARCHAR(255)    NOT NULL,
    role                        VARCHAR(255)    NOT NULL,   -- PENDING, USER, EXPERT, ADMIN
    enabled                     BOOLEAN         NOT NULL DEFAULT TRUE,
    last_login                  TIMESTAMP,
    reset_password_token        VARCHAR(255),
    reset_password_token_expiry TIMESTAMP
);


-- ---------------------------------------------------------------------------
-- TABLE : cuves (équipement physique)
-- ---------------------------------------------------------------------------
-- owner_email porte le cloisonnement multi-locataire : la clé de locataire est
-- l'adresse de l'utilisateur (cf. com.ifpc.api.security.Tenant).

CREATE TABLE cuves (
    id              BIGSERIAL        PRIMARY KEY,
    nom             VARCHAR(255)     NOT NULL,
    owner_email     VARCHAR(255),
    volume_max      DOUBLE PRECISION NOT NULL,
    statut_physique VARCHAR(255)     NOT NULL DEFAULT 'PROPRE',  -- PROPRE, SALE, EN_NETTOYAGE, EN_MAINTENANCE
    plan_x          DOUBLE PRECISION,
    plan_y          DOUBLE PRECISION,
    deleted         BOOLEAN          NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP,
    created_at      TIMESTAMP        NOT NULL,
    updated_at      TIMESTAMP        NOT NULL
);


-- ---------------------------------------------------------------------------
-- TABLE : lots (produit fluide / contenu)
-- ---------------------------------------------------------------------------
-- L'identifiant n'est pas unique globalement : deux exploitations peuvent
-- nommer un lot de la même façon. L'unicité attendue porte sur le couple
-- (owner_email, identifiant) — elle est décrite dans le modèle Lot mais n'est
-- déclarée par aucune contrainte, ni ici ni dans l'entité. À trancher.

CREATE TABLE lots (
    id              BIGSERIAL        PRIMARY KEY,
    identifiant     VARCHAR(100)     NOT NULL,
    owner_email     VARCHAR(255),
    type_produit    VARCHAR(100)     NOT NULL,
    volume_actuel   DOUBLE PRECISION NOT NULL DEFAULT 0,
    color_l         DOUBLE PRECISION,
    color_a         DOUBLE PRECISION,
    color_b         DOUBLE PRECISION,
    color_hex       VARCHAR(255),
    spectrum_json   TEXT,
    statut_lot      VARCHAR(30)      NOT NULL DEFAULT 'EN_FERMENTATION',  -- EN_FERMENTATION, PRET_A_ASSEMBLER, EMBOUTEILLE
    deleted         BOOLEAN          NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP,
    created_at      TIMESTAMP        NOT NULL,
    updated_at      TIMESTAMP        NOT NULL
);


-- ---------------------------------------------------------------------------
-- TABLE : stockages (relation cuve ↔ lot — où est le lot actuellement ?)
-- ---------------------------------------------------------------------------
-- date_fin NULL = stockage en cours.

CREATE TABLE stockages (
    id              BIGSERIAL        PRIMARY KEY,
    cuve_id         BIGINT           NOT NULL REFERENCES cuves(id),
    lot_id          BIGINT           NOT NULL REFERENCES lots(id),
    volume_occupe   DOUBLE PRECISION NOT NULL,
    date_debut      TIMESTAMP        NOT NULL,
    date_fin        TIMESTAMP
);


-- ---------------------------------------------------------------------------
-- TABLE : operations (journal des mouvements de chai)
-- ---------------------------------------------------------------------------

CREATE TABLE operations (
    id              BIGSERIAL        PRIMARY KEY,
    type            VARCHAR(30)      NOT NULL,  -- NETTOYAGE, REMPLISSAGE, TRANSFERT, TRANSFORMATION, ASSEMBLAGE
    cuve_source_id  BIGINT           REFERENCES cuves(id),
    cuve_dest_id    BIGINT           REFERENCES cuves(id),
    lot_id          BIGINT           REFERENCES lots(id),
    lot_resultat_id BIGINT           REFERENCES lots(id),
    volume          DOUBLE PRECISION,
    description     TEXT,
    user_email      VARCHAR(255),
    created_at      TIMESTAMP        NOT NULL
);


-- ---------------------------------------------------------------------------
-- TABLE : analysis_history (registre des analyses)
-- ---------------------------------------------------------------------------
-- Pièce de maîtrise sanitaire. Deux mécanismes la protègent :
--
--   scelle / jeton_resultat / resultat_jti
--     Un contrôle de pasteurisation n'est archivé que sur présentation d'un
--     jeton signé par le moteur de calcul. Le verdict, la VP, la cible et les
--     paramètres viennent de ce jeton, jamais du corps de la requête. Le jeton
--     est conservé pour rester vérifiable hors de l'application, et son
--     identifiant est unique : un même résultat ne peut pas être rejoué sur
--     plusieurs lots.
--
--   deleted / deleted_at / deleted_by
--     La suppression est logique. Une analyse retirée de l'historique reste au
--     registre, et l'opération est consignée au journal d'audit.

CREATE TABLE analysis_history (
    id              BIGSERIAL        PRIMARY KEY,
    type            VARCHAR(255)     NOT NULL,  -- controle, bareme, assemblage
    label           VARCHAR(255)     NOT NULL,
    lot_identifier  VARCHAR(255),
    statut          VARCHAR(255),
    vp              DOUBLE PRECISION,
    vp_cible        DOUBLE PRECISION,
    parametres      TEXT,
    courbe          TEXT,
    result_json     TEXT,
    user_email      VARCHAR(255),
    jeton_resultat  TEXT,
    resultat_jti    VARCHAR(64)      UNIQUE,
    scelle          BOOLEAN          NOT NULL DEFAULT FALSE,
    deleted         BOOLEAN          NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP,
    deleted_by      VARCHAR(255),
    created_at      TIMESTAMP        NOT NULL
);


-- ---------------------------------------------------------------------------
-- TABLE : audit_log (journal des opérations sensibles)
-- ---------------------------------------------------------------------------
-- En AJOUT SEUL : aucun point d'entrée de l'application ne modifie ni
-- n'efface une entrée. Consigne la suppression d'une analyse, un changement
-- de rôle, une modification de VP cible, une suppression de compte.
--
-- created_at est en TIMESTAMP WITH TIME ZONE, contrairement au reste du
-- modèle : une entrée de journal doit être datée sans ambiguïté.

CREATE TABLE audit_log (
    id              BIGSERIAL        PRIMARY KEY,
    action          VARCHAR(40)      NOT NULL,  -- ANALYSE_SUPPRIMEE, VP_CIBLE_MODIFIEE, ROLE_MODIFIE, COMPTE_APPROUVE, COMPTE_SUPPRIME
    cible_type      VARCHAR(40)      NOT NULL,  -- analyse, utilisateur, configuration produit
    cible_id        VARCHAR(255),
    acteur_email    VARCHAR(255),
    details         TEXT,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_log_created ON audit_log(created_at);
CREATE INDEX idx_audit_log_acteur ON audit_log(acteur_email);


-- ---------------------------------------------------------------------------
-- TABLE : help_text (contenus d'aide, modifiables en administration)
-- ---------------------------------------------------------------------------
-- La clé porte le suffixe de langue : « aide_bareme__fr », « aide_bareme__en ».

CREATE TABLE help_text (
    id              BIGSERIAL        PRIMARY KEY,
    text_key        VARCHAR(255)     NOT NULL UNIQUE,
    content         TEXT             NOT NULL,
    updated_at      TIMESTAMP
);


-- =============================================================================
-- FÉDÉRATION D'IDENTITÉ
-- =============================================================================
-- IFPC est fournisseur d'identité OpenID Connect pour d'autres plateformes :
-- un compte créé ici ouvre l'accès à des outils partenaires sans y recréer
-- d'identifiants. Spécification : docs/federation-identite.md.


-- ---------------------------------------------------------------------------
-- TABLE : habilitations_plateforme (qui accède à quelle plateforme fédérée)
-- ---------------------------------------------------------------------------
-- Le rôle IFPC (users.role) n'est jamais propagé aux plateformes fédérées : il
-- n'a de sens que sur PADOC — EXPERT y débloque les paramètres de barème hors
-- référentiel, ce qui ne veut rien dire ailleurs. Chaque plateforme a donc ses
-- propres rôles, ici (§7.2).
--
-- roles est du texte libre séparé par des virgules, et non un enum : ce
-- vocabulaire appartient à la plateforme cliente. Le figer imposerait de
-- redéployer le fournisseur d'identité à chaque rôle inventé par un partenaire.
--
-- Absence de ligne = absence d'accès. Le parcours d'autorisation est refusé
-- avant l'émission de tout code : un compte IFPC n'ouvre pas silencieusement
-- l'accès à tout outil qui rejoint la fédération.

CREATE TABLE habilitations_plateforme (
    id              BIGSERIAL        PRIMARY KEY,
    utilisateur_id  BIGINT           NOT NULL REFERENCES users(id),
    client_id       VARCHAR(100)     NOT NULL,   -- client_id OAuth de la plateforme
    roles           VARCHAR(500)     NOT NULL,   -- rôles côté plateforme, séparés par des virgules
    accorde_le      TIMESTAMP        NOT NULL,
    accorde_par     VARCHAR(255),                -- adresse de l'administrateur, pour l'audit
    CONSTRAINT uk_habilitation_utilisateur_client UNIQUE (utilisateur_id, client_id)
);


-- ---------------------------------------------------------------------------
-- TABLE : cles_signature (clés RSA de signature des jetons fédérés)
-- ---------------------------------------------------------------------------
-- Les jetons fédérés sont signés en RS256, pas avec le secret HS256 partagé
-- entre le Core API et le Calc Engine. En HS256, vérifier c'est signer :
-- communiquer ce secret à un partenaire lui donnerait le pouvoir de forger un
-- jeton d'administrateur IFPC (§4). Ici, seule la clé publique sort, au JWKS.
--
-- En base et non en mémoire : une clé tirée au démarrage changerait à chaque
-- redéploiement — tous les jetons de rafraîchissement en cours deviendraient
-- invérifiables — et deux instances signeraient avec des clés différentes.
--
-- La clé privée n'est pas chiffrée : il faudrait un second secret, détenu au
-- même endroit. La base est déjà dans la même frontière de confiance que
-- l'application, qui y lit aussi les empreintes de mots de passe.
--
-- Plusieurs clés coexistent pour la rotation : la plus récente non retirée
-- signe, toutes les non expirées restent publiées le temps que les jetons émis
-- avec l'ancienne expirent et que les caches clients se rafraîchissent.

CREATE TABLE cles_signature (
    kid             VARCHAR(64)      PRIMARY KEY,   -- identifiant publié au JWKS
    cle_publique    VARCHAR(4000)    NOT NULL,      -- X.509, base64
    cle_privee      VARCHAR(8000)    NOT NULL,      -- PKCS#8, base64
    creee_le        TIMESTAMP        NOT NULL,
    retiree_le      TIMESTAMP                       -- NULL = clé active (elle signe)
);


-- ---------------------------------------------------------------------------
-- TABLES : oauth2_* (Spring Authorization Server)
-- ---------------------------------------------------------------------------
-- Ces trois tables ne sont pas des entités JPA : les dépôts JDBC du serveur
-- d'autorisation les lisent et les écrivent directement, et Hibernate les
-- ignore. Elles sont donc créées explicitement au démarrage
-- (com.ifpc.api.security.federation.SchemaFederation), et SchemaDocumenteTest
-- ne les vérifie pas.
--
-- Le schéma est celui livré dans le jar de Spring Authorization Server, avec
-- les deux adaptations que le fichier amont demande pour PostgreSQL : « blob »
-- devient « text » (PostgreSQL n'a pas de blob) et « timestamp » devient
-- « timestamptz », sans quoi les instants se décalent.
--
--   oauth2_registered_client     les plateformes déclarées (client_id, URI de
--                                redirection exactes, portées, durées de vie)
--   oauth2_authorization         codes, jetons d'accès et de rafraîchissement
--                                en cours
--   oauth2_authorization_consent les consentements accordés par utilisateur
--                                et par plateforme
--
-- Voir SchemaFederation pour leur définition exacte.
