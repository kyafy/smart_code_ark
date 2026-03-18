package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotBlank;

public record SmsSendRequest(
        @NotBlank String phone,
        String scene
) {
}
