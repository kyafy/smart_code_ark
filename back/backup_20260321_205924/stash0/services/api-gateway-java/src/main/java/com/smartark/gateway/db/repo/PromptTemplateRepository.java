package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.PromptTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplateEntity, Long> {
    Optional<PromptTemplateEntity> findByTemplateKey(String templateKey);
}
