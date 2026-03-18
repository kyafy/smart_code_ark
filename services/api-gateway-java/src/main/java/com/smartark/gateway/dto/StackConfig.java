package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotBlank;

public record StackConfig(
        @NotBlank String backend,
        @NotBlank String frontend,
        @NotBlank String db
) {
}
