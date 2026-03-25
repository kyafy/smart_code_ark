package com.smartark.gateway.dto;

import java.util.Map;

public record LangchainMemoryWriteRequest(
        String scopeType,
        String scopeId,
        String memoryType,
        String content,
        Map<String, Object> metadata
) {
}
