package com.smartark.gateway.dto;

public record AuthResult(
        String userId,
        String email,
        String nickname,
        String token
) {
}
