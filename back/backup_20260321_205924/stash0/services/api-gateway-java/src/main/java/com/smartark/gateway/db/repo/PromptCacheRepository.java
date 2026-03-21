package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.PromptCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PromptCacheRepository extends JpaRepository<PromptCacheEntity, Long> {
    Optional<PromptCacheEntity> findByCacheKey(String cacheKey);
}
