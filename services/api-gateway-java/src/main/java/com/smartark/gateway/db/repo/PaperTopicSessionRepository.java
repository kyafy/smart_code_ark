package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaperTopicSessionRepository extends JpaRepository<PaperTopicSessionEntity, Long> {
    Optional<PaperTopicSessionEntity> findByTaskId(String taskId);

    Optional<PaperTopicSessionEntity> findByIdAndUserId(Long id, Long userId);
}
