package com.ifpc.api.repositories;

import com.ifpc.api.models.Fonctionnalite;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FonctionnaliteRepository extends JpaRepository<Fonctionnalite, String> {
}
