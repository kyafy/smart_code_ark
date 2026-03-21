package com.smartark.gateway.dto;

public record TaskLogDto(
        Long id,
        String level,
        String content,
        Long ts
) {
}