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
        Map<String, Object> output,
        // Phase 2: run_id distinguishes metrics/logs across retries of the same step.
        // Optional — old DeepAgent clients that don't send it produce null here.
        @JsonProperty("run_id") String runId
) {
}
