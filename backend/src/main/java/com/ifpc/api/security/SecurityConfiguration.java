package com.ifpc.api.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.DispatcherType;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfiguration {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthDebugFilter authDebugFilter;
    private final AuthenticationProvider authenticationProvider;

    /**
     * L'API métier — dernière chaîne, sans sélecteur, donc celle qui reçoit tout
     * ce que les précédentes n'ont pas pris.
     *
     * <p>L'ordre compte : le serveur d'autorisation (chaîne 1) et le formulaire
     * de connexion fédéré (chaîne 2) ont besoin d'une session, là où cette
     * chaîne-ci est volontairement sans état. Sans {@code @Order}, celle-ci
     * pourrait passer devant et appliquer sa politique {@code STATELESS} au
     * parcours OAuth, qui cesserait alors de fonctionner — l'utilisateur
     * reviendrait du formulaire de connexion sans être reconnu.</p>
     */
    @Bean
    @Order(3)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Spring Security 6 filtre aussi les répartitions internes vers
                // /error. En session STATELESS le contexte d'authentification
                // n'y existe plus : sans cette exception, toute erreur rendue
                // par ce chemin ressort en 403 au corps vide, masquant le refus
                // d'origine. ApiErrorHandler évite la répartition dans la
                // plupart des cas ; ceci couvre le reste.
                .authorizeHttpRequests(req -> req
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll())
                .authorizeHttpRequests(req ->
                        // Seuls l'authentification, la configuration produit et le
                        // marqueur de déploiement sont publics. Toutes les données
                        // métier (cuves, lots, stockages, opérations, historique)
                        // appartiennent à un locataire : elles exigent un jeton.
                        req.requestMatchers("/api/auth/**", "/api/config/**", "/api/deploy/**")
                                .permitAll()
                                .anyRequest()
                                .authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(authDebugFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Origines autorisées. Restriction <b>facultative</b>, et c'est délibéré.
     *
     * <p>{@code CORS_ALLOWED_ORIGINS} reçoit des origines séparées par des
     * virgules. <b>Non renseignée, toutes les origines sont acceptées.</b></p>
     *
     * <p><b>Pourquoi ce défaut permissif.</b> Une version antérieure faisait
     * l'inverse : à défaut de variable, elle retombait sur les origines de
     * développement. Le 30/09/2026, cela a coupé l'authentification en
     * production pendant des heures. Le raisonnement qui avait conduit à ce
     * défaut était faux : on croyait le CORS sans effet parce que le frontend
     * appelle l'API en relatif et que Next relaie côté serveur — mais le relais
     * <b>retransmet l'en-tête {@code Origin} du navigateur</b>, que Spring
     * évalue donc bel et bien. Résultat : 403 sur {@code POST /api/auth/login},
     * et seulement sur les POST, puisque les navigateurs n'envoient pas
     * {@code Origin} sur un GET de même origine.</p>
     *
     * <p><b>Pourquoi c'est acceptable.</b> Cette API s'authentifie par jeton
     * porté dans l'en-tête {@code Authorization}, jamais par cookie. Sans
     * {@code allowCredentials}, une origine tierce peut émettre une requête
     * mais ne peut pas y joindre le jeton de la victime, qu'elle n'a aucun
     * moyen de lire. Le caractère « ouvert » ne donne donc accès à rien de
     * plus qu'un appel non authentifié — ce que n'importe quel client HTTP
     * peut déjà faire.</p>
     *
     * <p>Restreindre reste souhaitable et se fait en une variable. Mais la
     * restriction doit être un choix explicite, pas un effet de bord d'un
     * oubli de configuration : un service en production ne doit pas cesser de
     * fonctionner parce qu'une variable facultative manque.</p>
     */
    @Value("${cors.allowed-origins:}")
    private String originesAutorisees;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origines = Arrays.stream(originesAutorisees.split(","))
                .map(String::trim)
                .filter(origine -> !origine.isEmpty())
                .toList();

        if (origines.isEmpty()) {
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            configuration.setAllowedOrigins(origines);
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setExposedHeaders(Arrays.asList(
                "Authorization",
                "X-IFPC-Backend-Marker",
                "X-IFPC-Railway-Commit",
                "X-IFPC-Railway-Deployment",
                "X-IFPC-Request-Method",
                "X-IFPC-Request-Path",
                "X-IFPC-Auth-Present",
                "X-IFPC-Auth-Name",
                "X-IFPC-Auth-Class",
                "X-IFPC-Auth-Authenticated",
                "X-IFPC-Auth-Authorities",
                "X-IFPC-Cuve-Controller"
        ));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
