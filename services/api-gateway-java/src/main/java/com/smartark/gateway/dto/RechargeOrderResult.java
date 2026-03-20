package com.smartark.gateway.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RechargeOrderResult(
        String orderId,
        String status,
        BigDecimal amount,
        Integer quota,
        String payChannel,
        String paymentNo,
        LocalDateTime paidAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
