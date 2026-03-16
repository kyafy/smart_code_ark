package com.smartark.gateway.dto;

import java.time.Instant;

public record AiTaskSubmitResult(
        String taskId,
        String status,
        Instant createdAt
) {
}
