package com.smartark.gateway.dto;

public record InternalTaskLogRequest(
        String level,
        String content
) {
}
