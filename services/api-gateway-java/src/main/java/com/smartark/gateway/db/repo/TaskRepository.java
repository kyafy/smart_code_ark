package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {
    List<TaskEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);
}
