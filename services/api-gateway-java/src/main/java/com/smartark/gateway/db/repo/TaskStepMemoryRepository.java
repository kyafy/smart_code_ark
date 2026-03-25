package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.TaskStepMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskStepMemoryRepository extends JpaRepository<TaskStepMemoryEntity, Long> {

    List<TaskStepMemoryEntity> findByTaskIdAndStepCode(String taskId, String stepCode);

    Optional<TaskStepMemoryEntity> findByTaskIdAndStepCodeAndMemoryKey(String taskId, String stepCode, String memoryKey);

    void deleteByTaskIdAndStepCode(String taskId, String stepCode);

    void deleteByTaskId(String taskId);
}
