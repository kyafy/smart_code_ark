package com.smartark.gateway.dto;

import java.util.List;

public record PreviewLogsResult(
        String taskId,
        List<LogLine> logs
) {
    public record LogLine(long ts, String level, String message) {
    }
}
