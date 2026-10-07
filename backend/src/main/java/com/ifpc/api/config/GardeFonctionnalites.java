package com.ifpc.api.config;

import com.ifpc.api.models.Role;
import com.ifpc.api.models.User;
import com.ifpc.api.services.ServiceFonctionnalites;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Refuse les appels aux fonctionnalités fermées.
 *
 * <p>Masquer l'entrée dans la navigation ne protège rien : l'adresse reste
 * tapable, et un écran retiré du menu continue de répondre. La décision de
 * l'administrateur doit donc aussi s'appliquer ici, sur le chemin des données.</p>
 *
 * <p>Un {@code HandlerInterceptor} et non un filtre : il s'exécute après la
 * chaîne de sécurité, donc avec un utilisateur déjà identifié. Un filtre placé
 * trop tôt ne verrait qu'un visiteur anonyme et fermerait tout aux
 * administrateurs.</p>
 *
 * <p><b>Portée.</b> Ce garde ne couvre que les routes servies par cette
 * application. La pasteurisation et la colorimétrie sont calculées par le
 * moteur Python, et l'assistant par le service documentaire : leurs appels ne
 * passent pas ici. C'est le intergiciel du front qui les arrête, et les deux
 * contrôles sont nécessaires.</p>
 */
@Configuration
@RequiredArgsConstructor
public class GardeFonctionnalites implements WebMvcConfigurer {

    /** Préfixe d'URL → clé de fonctionnalité. Le plus long gagne. */
    static final Map<String, String> ROUTES = new LinkedHashMap<>();

    static {
        ROUTES.put("/api/cuves", "cuves");
        ROUTES.put("/api/lots", "cuves");
        ROUTES.put("/api/stockages", "cuves");
        ROUTES.put("/api/operations", "cuves");
        ROUTES.put("/api/history", "historique");
    }

    private final ServiceFonctionnalites service;

    @Override
    public void addInterceptors(InterceptorRegistry registre) {
        registre.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest requete, HttpServletResponse reponse,
                                     Object handler) throws Exception {
                String cle = cleDe(requete.getRequestURI());
                if (cle == null || service.estAccessible(cle, estAdministrateur())) {
                    return true;
                }
                // 403 et non 404 : la ressource existe, elle est fermée. Un 404
                // enverrait l'équipe chercher une route disparue.
                reponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                reponse.setContentType("application/json;charset=UTF-8");
                reponse.getWriter().write(
                        "{\"message\":\"Fonctionnalité désactivée par un administrateur.\","
                        + "\"fonctionnalite\":\"" + cle + "\"}");
                return false;
            }
        }).addPathPatterns("/api/**");
    }

    static String cleDe(String uri) {
        if (uri == null) {
            return null;
        }
        String trouvee = null;
        String prefixeLePlusLong = "";
        for (Map.Entry<String, String> e : ROUTES.entrySet()) {
            String p = e.getKey();
            // « /api/lots » ne doit pas capturer « /api/lotsdivers » : on exige
            // la fin de l'URI ou un séparateur.
            boolean correspond = uri.equals(p) || uri.startsWith(p + "/") || uri.startsWith(p + "?");
            if (correspond && p.length() > prefixeLePlusLong.length()) {
                prefixeLePlusLong = p;
                trouvee = e.getValue();
            }
        }
        return trouvee;
    }

    private static boolean estAdministrateur() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated()
                && a.getPrincipal() instanceof User u && u.getRole() == Role.ADMIN;
    }
}
