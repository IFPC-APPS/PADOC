package com.ifpc.api.repositories;

import com.ifpc.api.models.HabilitationPlateforme;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HabilitationPlateformeRepository extends JpaRepository<HabilitationPlateforme, Long> {

    Optional<HabilitationPlateforme> findByUtilisateurIdAndClientId(Long utilisateurId, String clientId);

    List<HabilitationPlateforme> findByUtilisateurId(Long utilisateurId);

    List<HabilitationPlateforme> findByClientId(String clientId);
}
