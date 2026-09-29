package com.ifpc.api.config;

import com.ifpc.api.models.User;
import com.ifpc.api.repositories.UserRepository;
import com.ifpc.api.security.federation.AmorceClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseSeederTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AmorceClients amorceClients;

    @Test
    @DisplayName("seedDatabase répare le schéma et crée le compte admin quand la base est vide")
    void testSeedDatabaseEmpty() throws Exception {
        DatabaseSeeder seeder = new DatabaseSeeder();

        when(userRepository.findByEmail("admin@ifpc.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encodedAdmin");

        CommandLineRunner runner = seeder.seedDatabase(
                userRepository,
                passwordEncoder,
                jdbcTemplate,
                amorceClients
        );

        runner.run();

        // Le mot de passe initial est tiré au hasard : aucun secret en dur
        ArgumentCaptor<String> motDePasse = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(motDePasse.capture());
        assertNotEquals("admin", motDePasse.getValue());
        assertTrue(motDePasse.getValue().length() >= 24);

        verify(userRepository).save(any(User.class));
        verify(jdbcTemplate, atLeastOnce()).execute(anyString());
    }

    @Test
    @DisplayName("seedDatabase skips admin creation if already existing")
    void testSeedDatabaseExistingAdmin() throws Exception {
        DatabaseSeeder seeder = new DatabaseSeeder();

        User existingAdmin = User.builder().email("admin@ifpc.com").build();
        when(userRepository.findByEmail("admin@ifpc.com")).thenReturn(Optional.of(existingAdmin));

        CommandLineRunner runner = seeder.seedDatabase(
                userRepository,
                passwordEncoder,
                jdbcTemplate,
                amorceClients
        );

        runner.run();

        verify(userRepository, never()).save(any(User.class));
    }
}
