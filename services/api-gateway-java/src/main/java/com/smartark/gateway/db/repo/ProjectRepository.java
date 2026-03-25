package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {
    List<ProjectEntity> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId);

    Optional<ProjectEntity> findByIdAndDeletedAtIsNull(String id);
}
