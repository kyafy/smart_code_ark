package com.smartark.gateway.dto;

import java.util.List;

public record LangchainMemoryReadResult(
        List<MemoryItem> items
) {
    public record MemoryItem(
            String id,
            String scopeType,
            String scopeId,
            String memoryType,
            String content,
            Double score
    ) {
    }
}
