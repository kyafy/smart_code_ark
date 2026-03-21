package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatSendRequest(
        @NotBlank String sessionId,
        @NotBlank String message
) {
}
