package com.smartark.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Node-level execution metrics posted by DeepAgent after each graph node completes.
 * Schema follows docs/node-observability-spec.md.
 */
public record NodeMetricsPayload(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("run_id") String runId,
        String node,
        Integer round,
        String status,
        @JsonProperty("duration_ms") Long durationMs,
        @JsonProperty("model_calls") Integer modelCalls,
        Map<String, Integer> tokens,
        @JsonProperty("subtasks_total") Integer subtasksTotal,
        @JsonProperty("subtasks_finished") Integer subtasksFinished,
        Boolean degrade,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage
) {
}
