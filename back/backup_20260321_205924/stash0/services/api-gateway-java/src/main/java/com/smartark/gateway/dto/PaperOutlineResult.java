package com.smartark.gateway.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

public record PaperOutlineResult(
        String taskId,
        String citationStyle,
        String topic,
        String topicRefined,
        List<String> researchQuestions,
        JsonNode chapters,
        JsonNode manuscript,
        JsonNode qualityChecks,
        JsonNode references,
        BigDecimal qualityScore,
        Integer rewriteRound
) {
}
