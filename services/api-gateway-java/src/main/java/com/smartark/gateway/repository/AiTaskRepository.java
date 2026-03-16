package com.smartark.gateway.repository;

import com.smartark.gateway.entity.AiTaskEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiTaskRepository extends JpaRepository<AiTaskEntity, String> {
    Optional<AiTaskEntity> findByIdAndOwnerId(String id, String ownerId);

    Page<AiTaskEntity> findByOwnerId(String ownerId, Pageable pageable);
}
