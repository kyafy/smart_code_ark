package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.ModelCallAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCallAuditRepository extends JpaRepository<ModelCallAuditEntity, Long> {
}
