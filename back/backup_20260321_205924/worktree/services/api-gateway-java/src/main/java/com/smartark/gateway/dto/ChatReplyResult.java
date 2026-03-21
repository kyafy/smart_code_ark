package com.smartark.gateway.dto;

import java.util.List;
import java.util.Map;

public record ChatReplyResult(
        String sessionId,
        List<Map<String, String>> messages,
        Map<String, Object> extractedRequirements,
        String createdAt,
        String updatedAt
) {
}
