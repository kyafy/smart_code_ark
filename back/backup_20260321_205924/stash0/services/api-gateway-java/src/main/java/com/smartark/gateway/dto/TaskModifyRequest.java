package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaskModifyRequest(
        @NotBlank @Size(max = 10000) String changeInstructions
) {
}
