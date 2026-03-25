package com.smartark.gateway.dto;

import java.util.List;

public record RuntimeSmokeTestReportResult(
        boolean passed,
        boolean skipped,
        String deliveryLevelRequested,
        String smokeTarget,
        String startScript,
        ReusableRuntime reusableRuntime,
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

    public record ReusableRuntime(
            boolean availableForPreview,
            String runtimeId,
            Integer hostPort,
            String projectDir,
            String bootLogRef
    ) {
    }
}
