package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.TaskLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskLogRepository extends JpaRepository<TaskLogEntity, Long> {
    List<TaskLogEntity> findByTaskIdOrderByCreatedAtAsc(String taskId);
}
