package com.smartark.gateway.dto;

public record InternalTaskDispatchResult(
        String taskId,
        String status,
        String selectedMode,
        String reason
) {
}
