package com.smartark.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record InternalTaskStepUpdateRequest(
        @JsonProperty("step_code") String stepCode,
        String status,
        Integer progress,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("output_summary") String outputSummary,
        Map<String, Object> output
) {
}
