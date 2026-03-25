package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record TopicAdoptRequest(
        @NotNull Long sessionId,
        int selectedIndex,
        String customTitle,
        List<String> customQuestions
) {
}
