package com.smartark.gateway.dto;

import java.util.Map;

/**
 * Response payload for langchain-runtime graph execution APIs.
 */
public record LangchainGraphRunResult(
        String runId,
        String taskId,
        String graph,
        String status,
        Map<String, Object> result
) {
}
