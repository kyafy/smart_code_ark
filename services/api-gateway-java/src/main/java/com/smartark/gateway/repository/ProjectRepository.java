package com.smartark.gateway.repository;

import com.smartark.gateway.entity.ProjectEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {
    Page<ProjectEntity> findByOwnerId(String ownerId, Pageable pageable);

    Optional<ProjectEntity> findByIdAndOwnerId(String id, String ownerId);
}
