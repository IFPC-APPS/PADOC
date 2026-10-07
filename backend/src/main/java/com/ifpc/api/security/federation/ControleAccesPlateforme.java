package com.ifpc.api.security.federation;

import com.ifpc.api.models.Role;
import com.ifpc.api.models.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Dernier contrôle avant l'émission d'un code d'autorisation.
 *
 * <p>Tout compte IFPC validé accède aux plateformes de la fédération : avoir un
 * compte PADOC suffit, comme avoir un compte Google suffit pour « Se connecter
 * avec Google ». Ce que la personne peut faire une fois arrivée relève des
 * rôles transmis dans le jeton ({@link PersonnalisationJeton}), pas de ce
 * filtre.</p>
 *
 * <p>Un seul refus y est donc prononcé, <em>avant</em> que le moindre code ne
 * parte vers la plateforme cliente : le <b>compte en attente de validation</b>
 * (spec §8). Le formulaire de connexion le refuse déjà —
 * {@code User.isEnabled()} en tient compte — mais une session ouverte survit à
 * une rétrogradation vers {@code PENDING} décidée entre-temps par un
 * administrateur. Sans ce second contrôle, la fédération deviendrait une porte
 * de contournement de la validation manuelle.</p>
 *
 * <p>Exiger en plus une habilitation par plateforme fermait l'accès à tous,
 * administrateurs compris, tant que personne ne l'avait accordée une à une :
 * l'habilitation ne sert plus qu'à porter des rôles.</p>
 *
 * <p>Le refus est rendu en HTML : l'utilisateur est dans un navigateur, au
 * milieu d'une redirection. Lui répondre un JSON d'erreur afficherait une page
 * blanche de code brut.</p>
 */
@RequiredArgsConstructor
public class ControleAccesPlateforme extends OncePerRequestFilter {

    private final String cheminAutorisation;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest requete) {
        return !cheminAutorisation.equals(requete.getServletPath());
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest requete,
            @NonNull HttpServletResponse reponse,
            @NonNull FilterChain chaine
    ) throws ServletException, IOException {

        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentification != null
                && authentification.isAuthenticated()
                && authentification.getPrincipal() instanceof User utilisateur)) {
            // Pas encore connecté : laisser le serveur d'autorisation déclencher
            // la redirection vers le formulaire de connexion.
            chaine.doFilter(requete, reponse);
            return;
        }

        if (utilisateur.getRole() == Role.PENDING) {
            refuser(reponse, "Compte en attente de validation",
                    "Votre compte IFPC n'a pas encore été validé par un administrateur. "
                            + "Vous recevrez un courriel dès qu'il le sera.");
            return;
        }

        chaine.doFilter(requete, reponse);
    }

    private void refuser(HttpServletResponse reponse, String titre, String message) throws IOException {
        reponse.setStatus(HttpStatus.FORBIDDEN.value());
        reponse.setContentType(MediaType.TEXT_HTML_VALUE);
        reponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        reponse.getWriter().write("""
                <!doctype html>
                <html lang="fr"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s — IFPC</title>
                <style>
                  body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
                       background:#f6f7f5;color:#1f2420;display:flex;min-height:100vh;
                       align-items:center;justify-content:center;margin:0;padding:1.5rem}
                  main{background:#fff;border:1px solid #e2e5df;border-radius:12px;
                       padding:2rem;max-width:26rem}
                  h1{font-size:1.15rem;margin:0 0 .75rem}
                  p{margin:0;line-height:1.55;color:#4a524c}
                  .pied{margin-top:1.25rem;font-size:.75rem;color:#8b928a}
                  .pied a{color:#8b928a}
                </style></head>
                <body><main><h1>%s</h1><p>%s</p>
                <p class="pied"><a href="%s">En savoir plus sur la connexion IFPC</a></p>
                </main></body></html>
                """.formatted(titre, titre, message, DocumentationFederation.CHEMIN));
    }
}
