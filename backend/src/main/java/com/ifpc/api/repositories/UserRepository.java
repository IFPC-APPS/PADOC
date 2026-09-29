package com.ifpc.api.repositories;

import com.ifpc.api.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByExternalId(String externalId);
    Optional<User> findByResetPasswordToken(String resetPasswordToken);
}
