package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatStartRequest(
        @NotBlank String title,
        @NotBlank String projectType,
        String description
) {
}
