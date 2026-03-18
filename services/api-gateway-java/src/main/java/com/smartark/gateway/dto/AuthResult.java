package com.smartark.gateway.dto;

public record AuthResult(
        String token,
        long userId
) {
}
