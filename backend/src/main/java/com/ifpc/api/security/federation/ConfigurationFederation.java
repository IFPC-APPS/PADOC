package com.ifpc.api.security.federation;

import com.ifpc.api.repositories.HabilitationPlateformeRepository;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Le fournisseur d'identité OpenID Connect d'IFPC.
 *
 * <p><b>Pourquoi ici et pas dans le Calc Engine.</b> Le fournisseur d'identité
 * doit être le service qui détient les comptes : c'est ce module qui porte la
 * table {@code users}, le hachage des mots de passe, l'écran de connexion et
 * la validation des inscriptions. Faire émettre les jetons ailleurs imposerait
 * de dupliquer la frontière de confiance la plus sensible du système pour n'y
 * rien gagner. Le nombre d'endroits capables d'affirmer « cet utilisateur est
 * authentifié » doit rester égal à un (spec fédération §5.1).</p>
 *
 * <p><b>Trois chaînes de filtres, dans cet ordre.</b> Elles ne peuvent pas être
 * fusionnées : la première et la deuxième ont besoin d'une session HTTP (le
 * parcours OAuth se déroule dans un navigateur, sur plusieurs requêtes), là où
 * l'API métier est volontairement sans état.</p>
 *
 * <ol>
 *   <li>les points d'entrée du serveur d'autorisation ;</li>
 *   <li>le formulaire de connexion du parcours fédéré ;</li>
 *   <li>l'API métier existante, inchangée ({@code SecurityConfiguration}).</li>
 * </ol>
 */
@Configuration
public class ConfigurationFederation {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationFederation.class);

    public static final String CHEMIN_CONNEXION = "/federation/connexion";

    /**
     * Adresse vers laquelle renvoyer un visiteur non authentifié.
     *
     * <p>Absolue, et construite sur l'émetteur, quand celui-ci est déclaré.
     * C'est indispensable dès que le serveur est servi derrière un relais qui
     * réécrit l'hôte — le cas d'un hébergement où une adresse publique relaie
     * vers un hôte interne.</p>
     *
     * <p>Avec un chemin relatif, le conteneur fabrique la redirection à partir
     * de l'hôte qu'il voit, c'est-à-dire l'hôte INTERNE. Le navigateur change
     * alors de domaine en cours de parcours, et comme le cookie de session a
     * été posé sur le domaine public, il ne le suit pas : le serveur ouvre une
     * session neuve, où la demande d'autorisation mémorisée n'existe pas. La
     * connexion réussit, puis l'utilisateur est renvoyé sur « / » — sans la
     * moindre indication de ce qui s'est passé.</p>
     *
     * <p>Les en-têtes de relais ne suffisent pas à rattraper cela quand deux
     * relais se succèdent : le second réécrit {@code X-Forwarded-Host} avec le
     * sien, et l'adresse publique est définitivement perdue.</p>
     */
    /**
     * Ramène une adresse absolue sur l'origine publique.
     *
     * <p>Spring mémorise la demande d'autorisation telle qu'il l'a VUE
     * arriver, c'est-à-dire avec l'hôte interne : un relais placé devant
     * réécrit l'hôte, et l'adresse publique ne lui parvient pas. Renvoyer
     * l'utilisateur vers cette adresse lui fait changer de domaine, son cookie
     * de session ne le suit pas, et il se retrouve non authentifié — donc
     * renvoyé au formulaire qu'il vient de remplir.</p>
     *
     * <p>Seuls le chemin et la requête sont conservés ; l'origine devient celle
     * de l'émetteur, la seule que le navigateur ait jamais connue.</p>
     */
    static String surEmetteur(String adresseAbsolue, String emetteur) {
        if (adresseAbsolue == null || emetteur == null || emetteur.isBlank()) {
            return adresseAbsolue;
        }
        try {
            java.net.URI u = java.net.URI.create(adresseAbsolue);
            String chemin = u.getRawPath() == null ? "/" : u.getRawPath();
            String requete = u.getRawQuery() == null ? "" : "?" + u.getRawQuery();
            return emetteur.replaceAll("/+$", "") + chemin + requete;
        } catch (IllegalArgumentException adresseIllisible) {
            // Adresse inexploitable : mieux vaut la racine publique qu'une
            // redirection vers une destination qu'on ne sait pas lire.
            return emetteur.replaceAll("/+$", "") + "/";
        }
    }

    private static String adresseConnexion(String emetteur) {
        if (emetteur == null || emetteur.isBlank()) {
            return CHEMIN_CONNEXION;
        }
        return emetteur.replaceAll("/+$", "") + CHEMIN_CONNEXION;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain chaineServeurAutorisation(
            HttpSecurity http,
            HabilitationPlateformeRepository habilitations,
            AuthorizationServerSettings reglages,
            @Value("${federation.issuer:}") String emetteur
    ) throws Exception {

        OAuth2AuthorizationServerConfigurer configurateur =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        RequestMatcher pointsDEntree = configurateur.getEndpointsMatcher();

        http
                .securityMatcher(pointsDEntree)
                // oidc() active la découverte, /userinfo et la déconnexion
                // initiée par la plateforme cliente. Sans lui, on n'aurait
                // qu'OAuth2 : la plateforme devrait alors coder en dur chaque
                // URL au lieu de lire le document de découverte (spec §6.1).
                .with(configurateur, serveur -> serveur.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(requetes -> requetes.anyRequest().authenticated())
                // Ces points d'entrée sont appelés par des machines avec un
                // jeton ou un code à usage unique, jamais depuis un formulaire
                // porteur de cookie : le CSRF n'y a pas de prise, et l'exiger
                // casserait l'échange de code. C'est la configuration amont.
                // CORS désactivé sur cette chaîne. Spring Security applique
                // d'office le bean « corsConfigurationSource » à TOUTES les
                // chaînes, et celui-ci est enregistré sur /** avec la liste
                // d'origines de l'API. Or rien ici n'est appelé en XHR depuis
                // un navigateur : /oauth2/token et /oauth2/jwks le sont de
                // serveur à serveur, et /oauth2/authorize par une navigation.
                // Soumettre ces routes à une liste blanche d'origines ne
                // protège rien et les casse dès que l'origine réelle diffère
                // de celle qu'on avait prévue.
                .cors(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.ignoringRequestMatchers(pointsDEntree))
                // Un navigateur non authentifié arrivant sur /oauth2/authorize
                // doit voir le formulaire de connexion, pas un 401 nu.
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint(adresseConnexion(emetteur)),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // /userinfo s'authentifie par jeton d'accès, pas par session.
                .oauth2ResourceServer(serveur -> serveur.jwt(Customizer.withDefaults()))
                // Placé juste après le filtre qui restaure le contexte de
                // sécurité : l'utilisateur y est connu, et l'on est encore très
                // en amont de l'émission du code.
                .addFilterAfter(
                        new ControleAccesPlateforme(habilitations, reglages.getAuthorizationEndpoint()),
                        SecurityContextHolderFilter.class);

        return http.build();
    }

    /**
     * Le formulaire de connexion du parcours fédéré.
     *
     * <p>Distinct de celui de PADOC, qui vit dans le frontend Next.js et
     * s'authentifie par jeton porté en en-tête. Ici il faut une session : le
     * parcours OAuth enchaîne plusieurs requêtes du navigateur, et c'est
     * elle qui porte l'état entre la connexion et le retour sur
     * {@code /oauth2/authorize}.</p>
     */
    @Bean
    @Order(2)
    public SecurityFilterChain chaineConnexionFederation(
            HttpSecurity http,
            @Value("${federation.issuer:}") String emetteur) throws Exception {
        http
                .securityMatcher("/federation/**")
                // Même raison : un formulaire de connexion est une navigation,
                // pas un appel XHR. Le filtre CORS le refusait avec « Invalid
                // CORS request » — un 403 sans rapport avec les identifiants,
                // et donc indéchiffrable pour qui le reçoit.
                .cors(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requetes -> requetes
                        // Le guide d'intégration est public, comme le document de
                        // découverte qu'il commente : son lecteur est un
                        // développeur partenaire, qui n'a pas de compte IFPC.
                        .requestMatchers(CHEMIN_CONNEXION, DocumentationFederation.CHEMIN).permitAll()
                        .anyRequest().authenticated())
                .formLogin(formulaire -> formulaire
                        // Page absolue, traitement relatif : le formulaire est
                        // servi sous l'adresse publique, et son envoi part vers
                        // la même origine — donc avec le bon cookie de session.
                        .loginPage(adresseConnexion(emetteur))
                        .loginProcessingUrl(CHEMIN_CONNEXION)
                        .failureUrl(adresseConnexion(emetteur) + "?erreur")
                        // Le gestionnaire par défaut renvoie vers la demande
                        // mémorisée telle quelle, donc vers l'hôte interne. On
                        // la ramène sur l'origine publique, faute de quoi le
                        // cookie de session ne suit pas et l'utilisateur est
                        // réexpédié au formulaire qu'il vient de remplir.
                        .successHandler(succesConnexion(emetteur)))
                .logout(deconnexion -> deconnexion
                        .logoutUrl("/federation/deconnexion")
                        .logoutSuccessUrl(adresseConnexion(emetteur) + "?deconnecte"));
        return http.build();
    }

    private static AuthenticationSuccessHandler succesConnexion(String emetteur) {
        RequestCache cache = new HttpSessionRequestCache();
        return (requete, reponse, authentification) -> {
            SavedRequest memorisee = cache.getRequest(requete, reponse);
            // Consommée : une demande relue indéfiniment finit par former une
            // boucle si elle ramène au formulaire.
            cache.removeRequest(requete, reponse);

            String suite = memorisee == null
                    ? null
                    : surEmetteur(memorisee.getRedirectUrl(), emetteur);
            if (suite == null || suite.contains(CHEMIN_CONNEXION)) {
                suite = (emetteur == null || emetteur.isBlank())
                        ? "/" : emetteur.replaceAll("/+$", "") + "/";
            }
            reponse.sendRedirect(suite);
        };
    }

    // ── Persistance ──────────────────────────────────────────────────────────
    // Les trois services du serveur d'autorisation vont en base plutôt qu'en
    // mémoire. En mémoire, un redéploiement effacerait clients, consentements
    // et jetons de rafraîchissement, et deux instances ne verraient pas les
    // mêmes : un code émis par l'une serait inconnu de l'autre.

    // Le paramètre « schema » n'est pas utilisé dans le corps de ces méthodes :
    // il est là pour forcer l'ordre de création. Les dépôts JDBC du serveur
    // d'autorisation lisent les métadonnées des colonnes dans leur constructeur,
    // et se configurent de travers si les tables n'existent pas encore
    // (cf. SchemaFederation).
    @Bean
    public RegisteredClientRepository registeredClientRepository(
            JdbcTemplate jdbcTemplate, SchemaFederation schema) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository clients, SchemaFederation schema) {
        return new MagasinAutorisations(jdbcTemplate, clients);
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository clients, SchemaFederation schema) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, clients);
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(GestionnaireCles gestionnaire) {
        return gestionnaire.sourceDeCles();
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> sourceDeCles) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(sourceDeCles);
    }

    /**
     * L'encodeur, avec la clé de signature désignée explicitement.
     *
     * <p><b>Pourquoi ce bean existe.</b> La même source alimente le JWKS, qui
     * publie <em>plusieurs</em> clés pendant le recouvrement d'une rotation, et
     * l'encodeur, qui doit en employer <em>une</em>. Sans sélecteur, l'encodeur
     * par défaut refuse de signer dès qu'il en voit deux : « Failed to select a
     * key since there are multiple for the signing algorithm ».</p>
     *
     * <p>Le défaut est sournois — il n'apparaît pas à la mise en service, mais à
     * la première rotation de clé, donc des semaines plus tard, et il arrête
     * alors toute émission de jeton.</p>
     */
    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> sourceDeCles, GestionnaireCles gestionnaire) {
        NimbusJwtEncoder encodeur = new NimbusJwtEncoder(sourceDeCles);
        encodeur.setJwkSelector(cles -> {
            String kidActif = gestionnaire.kidActif();
            return cles.stream()
                    .filter(cle -> kidActif != null && kidActif.equals(cle.getKeyID()))
                    .findFirst()
                    // Repli : aucune clé active identifiée (cas théorique, toutes
                    // retirées). Mieux vaut signer avec la plus récente publiée
                    // que refuser toute authentification.
                    .orElseGet(() -> cles.isEmpty() ? null : cles.get(0));
        });
        return encodeur;
    }

    /**
     * L'émetteur ({@code iss}) et l'emplacement des points d'entrée.
     *
     * <p>L'émetteur doit être l'URL publique du service : c'est la valeur que
     * les plateformes clientes compareront au claim {@code iss}, et celle sur
     * laquelle elles construiront l'URL du JWKS. Faute de configuration, le
     * serveur la déduit de la requête reçue — ce qui suffit en développement
     * mais se laisse dicter par un en-tête {@code Host} derrière un proxy.</p>
     */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings(
            @Value("${federation.issuer:}") String emetteur) {
        AuthorizationServerSettings.Builder reglages = AuthorizationServerSettings.builder();
        if (emetteur != null && !emetteur.isBlank()) {
            reglages.issuer(emetteur.trim());
        } else {
            log.warn("Fédération : FEDERATION_ISSUER n'est pas défini, l'émetteur sera déduit "
                    + "de chaque requête. Acceptable en développement, à définir en production.");
        }
        return reglages.build();
    }
}
