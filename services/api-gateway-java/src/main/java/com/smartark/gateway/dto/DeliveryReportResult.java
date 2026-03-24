package com.smartark.gateway.dto;

import java.util.List;

public record DeliveryReportResult(
        String taskId,
        String deliveryLevelRequested,
        String deliveryLevelActual,
        String status,
        boolean passed,
        List<IssueItem> blockingIssues,
        List<String> warnings,
        ReportRefs reports,
        String generatedAt
) {
    public record IssueItem(
            String stage,
            String code,
            String message,
            String logRef
    ) {
    }

    public record ReportRefs(
            String contractReportUrl,
            String buildReportUrl,
            String runtimeSmokeReportUrl
    ) {
    }
}
