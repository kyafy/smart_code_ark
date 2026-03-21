package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.ProjectSpecEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectSpecRepository extends JpaRepository<ProjectSpecEntity, Long> {
    Optional<ProjectSpecEntity> findTopByProjectIdOrderByVersionDesc(String projectId);
}
