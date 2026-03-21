package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotBlank;

public record PaperOutlineGenerateRequest(
        @NotBlank String topic,
        @NotBlank String discipline,
        @NotBlank String degreeLevel,
        String methodPreference
) {
}
