package com.smartark.gateway.dto;

import java.util.Map;

/**
 * Request payload for langchain-runtime graph execution APIs.
 */
public record LangchainGraphRunRequest(
        String taskId,
        String projectId,
        String userId,
        Map<String, Object> input
) {
}
