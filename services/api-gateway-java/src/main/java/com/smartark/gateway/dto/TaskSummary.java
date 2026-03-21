package com.smartark.gateway.dto;

public record TaskSummary(
        String id,
        String projectId,
        String taskType,
        String status,
        Integer progress,
        String errorMessage,
        String createdAt,
        String updatedAt
) {
}