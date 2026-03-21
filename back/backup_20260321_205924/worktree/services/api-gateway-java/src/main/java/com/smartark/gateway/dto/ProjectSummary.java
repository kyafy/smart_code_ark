package com.smartark.gateway.dto;

public record ProjectSummary(
        String id,
        String title,
        String description,
        String status,
        String updatedAt
) {
}
