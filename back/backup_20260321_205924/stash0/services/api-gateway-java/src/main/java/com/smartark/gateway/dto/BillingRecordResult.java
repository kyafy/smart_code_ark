package com.smartark.gateway.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BillingRecordResult(
        Long id,
        String projectId,
        String taskId,
        BigDecimal changeAmount,
        String currency,
        String reason,
        BigDecimal balanceAfter,
        LocalDateTime createdAt
) {
}
