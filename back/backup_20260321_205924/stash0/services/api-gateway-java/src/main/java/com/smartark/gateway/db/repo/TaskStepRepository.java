package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.TaskStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskStepRepository extends JpaRepository<TaskStepEntity, Long> {
    List<TaskStepEntity> findByTaskIdOrderByStepOrderAsc(String taskId);

    Optional<TaskStepEntity> findByTaskIdAndStepCode(String taskId, String stepCode);
    List<TaskStepEntity> findByTaskIdAndStatus(String taskId, String status);
}
