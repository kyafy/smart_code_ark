package com.smartark.gateway.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record RechargeOrderCreateRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull @Positive Integer quota,
        @NotBlank String payChannel
) {
}
