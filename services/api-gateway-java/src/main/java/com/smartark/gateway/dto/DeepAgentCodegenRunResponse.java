package com.smartark.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeepAgentCodegenRunResponse(
        @JsonProperty("run_id") String runId,
        @JsonProperty("task_id") String taskId,
        String status
) {
}
