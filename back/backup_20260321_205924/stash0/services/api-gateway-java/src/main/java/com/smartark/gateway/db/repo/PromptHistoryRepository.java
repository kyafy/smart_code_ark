package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.PromptHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromptHistoryRepository extends JpaRepository<PromptHistoryEntity, Long> {
    List<PromptHistoryEntity> findByTaskIdOrderByCreatedAtDesc(String taskId);
}
