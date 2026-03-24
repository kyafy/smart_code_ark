package com.smartark.gateway.dto;

public record LangchainContextBuildRequest(
        String taskId,
        String stepCode,
        String projectId,
        Long userId,
        String instructions,
        Integer maxItems
) {
}
