package com.smartark.gateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProjectConfirmRequest(
        @NotBlank String sessionId,
        @Valid @NotNull StackConfig stack,
        String description,
        String prd
) {
}
