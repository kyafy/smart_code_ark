package com.smartark.gateway.dto;

public record TaskPreviewResult(
        String taskId,
        String status,
        String phase,
        String previewUrl,
        String expireAt,
        String lastError,
        Integer lastErrorCode,
        String buildLogUrl
) {
}
