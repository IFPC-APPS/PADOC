package com.ifpc.api.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Le schéma documenté suit les entités.
 *
 * <p>Le défaut d'origine : {@code docs/schema.sql} décrivait quatre tables sur
 * neuf, et dans une version antérieure au cloisonnement multi-locataire — sans
 * {@code owner_email}, sans la suppression logique des lots, avec une
 * contrainte d'unicité qui n'existe plus. Les tables les plus sensibles
 * (comptes, registre des analyses) n'y figuraient pas du tout.</p>
 *
 * <p>Le schéma réel étant produit par Hibernate, rien ne signalait l'écart.
 * Ce test le signale : une table ou une colonne ajoutée à une entité sans être
 * reportée au document fait échouer la construction.</p>
 */
class SchemaDocumenteTest {

    private static final Path SCHEMA = Path.of("..", "docs", "schema.sql");

    private static String schema() {
        try {
            return Files.readString(SCHEMA);
        } catch (IOException e) {
            throw new IllegalStateException("schéma documenté introuvable : " + SCHEMA.toAbsolutePath(), e);
        }
    }

    /** Toutes les entités JPA du modèle, découvertes par balayage du paquet. */
    private static List<Class<?>> entites() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.ifpc.api.models")) {
            try {
                classes.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return classes;
    }

    private static String nomDeTable(Class<?> entite) {
        Table table = entite.getAnnotation(Table.class);
        return table != null && !table.name().isBlank()
                ? table.name()
                : versSnakeCase(entite.getSimpleName());
    }

    /** Convention de nommage par défaut de Spring Boot : camelCase → snake_case. */
    private static String versSnakeCase(String nom) {
        return nom.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String nomDeColonne(Field champ) {
        Column colonne = champ.getAnnotation(Column.class);
        if (colonne != null && !colonne.name().isBlank()) {
            return colonne.name();
        }
        JoinColumn jointure = champ.getAnnotation(JoinColumn.class);
        if (jointure != null && !jointure.name().isBlank()) {
            return jointure.name();
        }
        return versSnakeCase(champ.getName());
    }

    private static Set<String> colonnesAttendues(Class<?> entite) {
        Set<String> colonnes = new LinkedHashSet<>();
        for (Field champ : entite.getDeclaredFields()) {
            if (Modifier.isStatic(champ.getModifiers()) || champ.isSynthetic()) continue;
            if (champ.isAnnotationPresent(Transient.class)) continue;
            colonnes.add(nomDeColonne(champ));
        }
        return colonnes;
    }

    /** Corps du CREATE TABLE portant ce nom, ou null s'il n'y en a pas. */
    private static String corpsDeTable(String nomTable) {
        Matcher m = Pattern
                .compile("CREATE TABLE\\s+" + Pattern.quote(nomTable) + "\\s*\\((.*?)\\n\\);",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
                .matcher(schema());
        return m.find() ? m.group(1) : null;
    }

    private static Set<String> colonnesDocumentees(String corps) {
        Set<String> colonnes = new LinkedHashSet<>();
        for (String ligne : corps.split("\n")) {
            String nette = ligne.replaceAll("--.*$", "").trim();
            Matcher m = Pattern.compile("^([a-z_][a-z0-9_]*)\\s+\\S").matcher(nette);
            if (m.find() && !List.of("constraint", "primary", "foreign", "unique", "check").contains(m.group(1))) {
                colonnes.add(m.group(1));
            }
        }
        return colonnes;
    }

    static Stream<Class<?>> toutesLesEntites() {
        return entites().stream();
    }

    @Test
    @DisplayName("le modèle compte bien les entités attendues")
    void leModeleEstEntierementBalaye() {
        Set<String> tables = new LinkedHashSet<>();
        entites().forEach(e -> tables.add(nomDeTable(e)));
        assertEquals(
                Set.of("users", "cuves", "lots", "stockages", "operations",
                        "analysis_history", "audit_log", "help_text",
                        // Fédération d'identité (docs/federation-identite.md).
                        // Les tables oauth2_* n'y figurent pas : elles ne sont
                        // pas des entités JPA, le serveur d'autorisation les
                        // gère en JDBC direct.
                        "habilitations_plateforme", "cles_signature",
                        // Fonctionnalités ouvertes ou fermées au public, réglées
                        // depuis l'écran d'administration.
                        "fonctionnalites"),
                tables,
                "une entité a été ajoutée ou retirée : mettre à jour docs/schema.sql et ce test");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("toutesLesEntites")
    @DisplayName("chaque entité a sa table au document")
    void chaqueEntiteEstDocumentee(Class<?> entite) {
        String table = nomDeTable(entite);
        assertNotNull(corpsDeTable(table),
                "table « " + table + " » absente de docs/schema.sql (entité " + entite.getSimpleName() + ")");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("toutesLesEntites")
    @DisplayName("chaque colonne d'entité figure au document")
    void chaqueColonneEstDocumentee(Class<?> entite) {
        String table = nomDeTable(entite);
        String corps = corpsDeTable(table);
        assertNotNull(corps, "table « " + table + " » absente du document");

        Set<String> documentees = colonnesDocumentees(corps);
        for (String attendue : colonnesAttendues(entite)) {
            assertTrue(documentees.contains(attendue),
                    "colonne « " + table + "." + attendue + " » absente de docs/schema.sql "
                            + "— documentées : " + documentees);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("toutesLesEntites")
    @DisplayName("le document ne décrit pas de colonne disparue du modèle")
    void aucuneColonneFantome(Class<?> entite) {
        String table = nomDeTable(entite);
        Set<String> attendues = colonnesAttendues(entite);
        for (String documentee : colonnesDocumentees(corpsDeTable(table))) {
            assertTrue(attendues.contains(documentee),
                    "colonne « " + table + "." + documentee + " » documentée mais absente de "
                            + entite.getSimpleName() + " — le document décrit une base périmée");
        }
    }
}
