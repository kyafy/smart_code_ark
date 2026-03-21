package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.ArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, Long> {
    List<ArtifactEntity> findByTaskIdOrderByCreatedAtAsc(String taskId);
}
