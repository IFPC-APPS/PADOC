package com.ifpc.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        // Seules les routes réellement publiques échappent à l'authentification.
        // Les cuves y figuraient tant qu'elles étaient partagées : désormais
        // elles appartiennent à un locataire et doivent porter un principal,
        // sans quoi le contrôleur ne saurait pas à qui répondre.
        //
        // /api/config n'y figure PLUS. Ces routes restent ouvertes — c'est la
        // chaîne de sécurité qui en décide, pas ce filtre — mais certaines ont
        // besoin de savoir QUI demande : l'état des fonctionnalités répond tout
        // ouvert à un administrateur, et le reste du public ne doit pas en
        // profiter. Écarté d'ici, le filtre ne s'exécutait pas, le contexte
        // restait anonyme, et tout administrateur subissait les fermetures
        // qu'il venait lui-même de décider.
        //
        // Sans risque pour les routes réellement anonymes : sans en-tête
        // « Authorization », le filtre passe la main sans rien faire, et un
        // jeton invalide se solde par un contexte vide, pas par un refus.
        String path = request.getServletPath();

        return path.startsWith("/api/deploy/");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String userEmail;

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        jwt = authHeader.substring(7);
        try {
            userEmail = jwtService.extractUsername(jwt);

            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);
                if (jwtService.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    authToken.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                    );
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception ignored) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
