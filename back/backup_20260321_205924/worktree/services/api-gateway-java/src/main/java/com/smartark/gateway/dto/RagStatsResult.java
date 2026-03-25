package com.smartark.gateway.dto;

public record RagStatsResult(
        long totalPoints,
        long totalDocs,
        long totalChunks,
        String collectionName,
        String status
) {
}
