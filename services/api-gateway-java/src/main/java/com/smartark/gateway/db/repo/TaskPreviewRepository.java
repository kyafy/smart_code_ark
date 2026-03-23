package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.TaskPreviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskPreviewRepository extends JpaRepository<TaskPreviewEntity, Long> {
    Optional<TaskPreviewEntity> findByTaskId(String taskId);
    List<TaskPreviewEntity> findByProjectId(String projectId);
    long countByUserIdAndStatusIn(Long userId, Collection<String> statuses);
    List<TaskPreviewEntity> findByStatusAndExpireAtBefore(String status, LocalDateTime expireAt);
    List<TaskPreviewEntity> findByStatusInAndUpdatedAtBefore(Collection<String> statuses, LocalDateTime before);
    List<TaskPreviewEntity> findByStatus(String status);
}
