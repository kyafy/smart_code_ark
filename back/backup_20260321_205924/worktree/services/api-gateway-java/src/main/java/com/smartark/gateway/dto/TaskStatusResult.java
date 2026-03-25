package com.smartark.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TaskStatusResult(
        String status,
        Integer progress,
        String step,
        @JsonProperty("current_step") String currentStep,
        String projectId,
        String errorCode,
        String errorMessage,
        String startedAt,
        String finishedAt
) {
}
