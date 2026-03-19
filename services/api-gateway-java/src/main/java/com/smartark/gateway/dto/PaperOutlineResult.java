package com.smartark.gateway.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record PaperOutlineResult(
        String taskId,
        String citationStyle,
        String topic,
        String topicRefined,
        List<String> researchQuestions,
        JsonNode chapters,
        JsonNode qualityChecks,
        JsonNode references
) {
}
