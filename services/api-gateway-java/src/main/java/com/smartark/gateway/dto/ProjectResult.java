package com.smartark.gateway.dto;

import java.time.Instant;

public record ProjectResult(
        String id,
        String ownerId,
        String name,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
