package com.smartark.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record DeepAgentCodegenRunRequest(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("project_id") String projectId,
        @JsonProperty("user_id") Long userId,
        String instructions,
        String prd,
        StackConfig stack,
        @JsonProperty("template_id") String templateId,
        @JsonProperty("workspace_dir") String workspaceDir,
        @JsonProperty("callback_base_url") String callbackBaseUrl,
        @JsonProperty("callback_api_key") String callbackApiKey,
        @JsonProperty("sandbox_config") Map<String, Object> sandboxConfig
) {
    public record StackConfig(
            String backend,
            String frontend,
            String db
    ) {
    }
}
