package com.ifpc.api.repositories;

import com.ifpc.api.models.CleSignature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CleSignatureRepository extends JpaRepository<CleSignature, String> {

    /** Les clés encore publiables, la plus récente d'abord. */
    List<CleSignature> findAllByOrderByCreeeLeDesc();
}
