package com.smartark.gateway.dto;

import java.util.List;

public record TopicSuggestResult(
        Long sessionId,
        int round,
        List<SuggestedTopic> suggestions
) {
    public record SuggestedTopic(
            String title,
            List<String> researchQuestions,
            String rationale,
            List<String> keywords
    ) {
    }
}
