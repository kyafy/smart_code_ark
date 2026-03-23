package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotBlank;

public record TopicSuggestRequest(
        Long sessionId,
        @NotBlank String direction,
        String constraints,
        Integer round
) {
}
