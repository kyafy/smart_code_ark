package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.RechargeOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RechargeOrderRepository extends JpaRepository<RechargeOrderEntity, Long> {
    Optional<RechargeOrderEntity> findByOrderId(String orderId);
    Optional<RechargeOrderEntity> findByPaymentNoAndStatus(String paymentNo, String status);
}
