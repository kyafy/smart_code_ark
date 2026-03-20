package com.smartark.gateway.dto;

public record PaperProjectSummary(
        String taskId,
        String topic,
        String discipline,
        String degreeLevel,
        String status,
        String updatedAt
) {
}
