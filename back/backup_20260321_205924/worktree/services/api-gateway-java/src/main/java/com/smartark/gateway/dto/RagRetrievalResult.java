package com.smartark.gateway.dto;

import com.smartark.gateway.agent.model.RagEvidenceItem;

import java.util.List;

public record RagRetrievalResult(
        String taskId,
        List<RagEvidenceItem> evidenceItems,
        long totalChunksSearched
) {
}
