package com.ifpc.api.security;

import com.ifpc.api.models.Role;
import com.ifpc.api.models.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtService jwtService;
    @Mock private UserDetailsService userDetailsService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    @InjectMocks private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("shouldNotFilter : seule la sonde de déploiement échappe au filtre")
    void testShouldNotFilter() {
        // /api/config reste une route OUVERTE — la chaîne de sécurité en
        // décide — mais elle passe désormais par le filtre, car certaines de
        // ses réponses dépendent de QUI demande. L'état des fonctionnalités
        // répond tout ouvert à un administrateur ; écartée du filtre, la route
        // ne voyait que des anonymes, et l'administrateur subissait les
        // fermetures qu'il venait lui-même de décider.
        //
        // Sans risque pour un appel anonyme : sans en-tête « Authorization »,
        // le filtre passe la main sans rien faire, et un jeton invalide se
        // solde par un contexte vide plutôt que par un refus.
        when(request.getServletPath()).thenReturn("/api/config/products");
        assertFalse(filter.shouldNotFilter(request));

        when(request.getServletPath()).thenReturn("/api/config/fonctionnalites");
        assertFalse(filter.shouldNotFilter(request));

        // Sonde de supervision : interrogée sans jeton, et sans intérêt à
        // connaître l'appelant.
        when(request.getServletPath()).thenReturn("/api/deploy/info");
        assertTrue(filter.shouldNotFilter(request));

        // Les données d'un locataire doivent toujours être authentifiées, y
        // compris en lecture : sinon le contrôleur ne sait pas à qui répondre.
        when(request.getServletPath()).thenReturn("/api/cuves");
        assertFalse(filter.shouldNotFilter(request));

        when(request.getServletPath()).thenReturn("/api/lots");
        assertFalse(filter.shouldNotFilter(request));
    }

    @Test
    @DisplayName("doFilterInternal without Authorization header continues filter chain")
    void testNoAuthHeader() throws ServletException, IOException {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("doFilterInternal with invalid Bearer token continues without auth")
    void testInvalidBearerHeader() throws ServletException, IOException {
        when(request.getHeader("Authorization")).thenReturn("Basic 12345");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("doFilterInternal with valid token sets SecurityContext")
    void testValidToken() throws ServletException, IOException {
        String token = "valid-token";
        User user = User.builder().email("test@ifpc.eu").role(Role.USER).build();

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtService.extractUsername(token)).thenReturn("test@ifpc.eu");
        when(userDetailsService.loadUserByUsername("test@ifpc.eu")).thenReturn(user);
        when(jwtService.isTokenValid(token, user)).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("test@ifpc.eu", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    @DisplayName("doFilterInternal when token is invalid or username is null")
    void testInvalidTokenOrNullUsername() throws ServletException, IOException {
        String token = "invalid-token";
        User user = User.builder().email("test@ifpc.eu").role(Role.USER).build();

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtService.extractUsername(token)).thenReturn("test@ifpc.eu");
        when(userDetailsService.loadUserByUsername("test@ifpc.eu")).thenReturn(user);
        when(jwtService.isTokenValid(token, user)).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        // Null username
        when(jwtService.extractUsername(token)).thenReturn(null);
        filter.doFilterInternal(request, response, filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("doFilterInternal when SecurityContext is already populated")
    void testAlreadyAuthenticated() throws ServletException, IOException {
        String token = "token";
        User user = User.builder().email("test@ifpc.eu").role(Role.USER).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtService.extractUsername(token)).thenReturn("test@ifpc.eu");

        filter.doFilterInternal(request, response, filterChain);

        verify(userDetailsService, never()).loadUserByUsername(any());
    }

    @Test
    @DisplayName("doFilterInternal handles exception by clearing context")
    void testTokenException() throws ServletException, IOException {
        String token = "bad-token";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtService.extractUsername(token)).thenThrow(new RuntimeException("Expired token"));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
