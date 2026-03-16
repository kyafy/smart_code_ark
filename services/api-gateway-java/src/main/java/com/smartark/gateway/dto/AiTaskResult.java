package com.smartark.gateway.dto;

import java.time.Instant;

public record AiTaskResult(
        String taskId,
        String requirement,
        String status,
        DemoGenerateResult result,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt
) {
}
