package com.smartark.gateway.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

public record PaperManuscriptResult(
        String taskId,
        String topic,
        String topicRefined,
        JsonNode manuscript,
        BigDecimal qualityScore,
        Integer rewriteRound
) {
}
