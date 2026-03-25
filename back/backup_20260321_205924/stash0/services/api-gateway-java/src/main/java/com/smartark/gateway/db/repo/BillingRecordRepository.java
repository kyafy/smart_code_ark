package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.BillingRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingRecordRepository extends JpaRepository<BillingRecordEntity, Long> {
    List<BillingRecordEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}
