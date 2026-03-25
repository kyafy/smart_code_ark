package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.ModelRegistryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelRegistryRepository extends JpaRepository<ModelRegistryEntity, Long> {

    Optional<ModelRegistryEntity> findByModelName(String modelName);

    List<ModelRegistryEntity> findByEnabledTrueOrderByPriorityAsc();

    List<ModelRegistryEntity> findByModelRoleAndEnabledTrueOrderByPriorityAsc(String modelRole);

    List<ModelRegistryEntity> findAllByOrderByPriorityAsc();
}
