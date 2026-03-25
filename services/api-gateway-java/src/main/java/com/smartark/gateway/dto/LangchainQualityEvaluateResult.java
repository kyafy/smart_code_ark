package com.smartark.gateway.dto;

import java.util.List;

public record LangchainQualityEvaluateResult(
        boolean passed,
        List<String> failedRules,
        List<String> suggestions,
        Double score
) {
}
