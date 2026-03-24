package com.smartark.gateway.dto;

public record LangchainMemoryReadRequest(
        String scopeType,
        String scopeId,
        String query,
        Integer topK
) {
}
