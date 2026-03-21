package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {
    List<ProjectEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
