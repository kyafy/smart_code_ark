package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.PromptVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PromptVersionRepository extends JpaRepository<PromptVersionEntity, Long> {
    Optional<PromptVersionEntity> findByTemplateIdAndVersionNo(Long templateId, Integer versionNo);
}
