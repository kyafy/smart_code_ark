package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record RechargeCallbackRequest(
        @NotBlank String orderId,
        @NotBlank String paymentNo,
        @NotBlank String signature,
        BigDecimal paidAmount,
        String payChannel
) {
}
