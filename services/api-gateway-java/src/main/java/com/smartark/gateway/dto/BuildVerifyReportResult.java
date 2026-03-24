package com.smartark.gateway.dto;

import java.util.List;

public record BuildVerifyReportResult(
        boolean passed,
        boolean skipped,
        String deliveryLevelRequested,
        List<CommandResult> commands,
        List<IssueItem> blockingIssues,
        List<String> warnings,
        String generatedAt
) {
    public record CommandResult(
            String name,
            String command,
            String workdir,
            Integer exitCode,
            Long durationMs,
            String status,
            String logRef
    ) {
    }

    public record IssueItem(
            String code,
            String message,
            String logRef
    ) {
    }
}
