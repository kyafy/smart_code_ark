package com.smartark.gateway.dto;

public record TaskPreviewResult(
        String taskId,
        String status,
        String previewUrl,
        String expireAt,
        String lastError
) {
}
