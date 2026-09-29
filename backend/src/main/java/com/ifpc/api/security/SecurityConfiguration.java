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
     * Origines autorisées, en liste explicite.
     *
     * <p>{@code CORS_ALLOWED_ORIGINS} reçoit les origines séparées par des
     * virgules. Non renseignée, la valeur retombe sur les origines de
     * développement — et surtout plus sur {@code "*"} : ce service émet
     * désormais des jetons d'identité pour d'autres plateformes, et une origine
     * quelconque ne doit pas pouvoir lui adresser de requête créditée
     * (spec fédération §9).</p>
     */
    @Value("${cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}")
    private String originesAutorisees;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(originesAutorisees.split(","))
                .map(String::trim)
                .filter(origine -> !origine.isEmpty())
                .toList());
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
