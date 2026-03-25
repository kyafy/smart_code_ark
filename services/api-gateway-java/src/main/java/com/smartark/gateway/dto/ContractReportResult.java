package com.smartark.gateway.dto;

import java.util.List;

public record ContractReportResult(
        boolean passed,
        List<String> failedRules,
        List<String> fixedActions,
        String generatedAt
) {
}
