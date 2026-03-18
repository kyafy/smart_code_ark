package com.smartark.gateway.dto;

import java.util.List;
import java.util.Map;

public record ProjectDetail(
        String id,
        String title,
        String description,
        String projectType,
        String status,
        StackConfig stack,
        String requirementSpec,
        String createdAt,
        String updatedAt,
        List<TaskSummary> tasks,
        List<Map<String, String>> messages
) {
}
