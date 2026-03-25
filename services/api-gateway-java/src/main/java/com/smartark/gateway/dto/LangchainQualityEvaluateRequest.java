package com.smartark.gateway.dto;

import java.util.List;

public record LangchainQualityEvaluateRequest(
        String taskId,
        String stepCode,
        String content,
        List<String> rules
) {
}
