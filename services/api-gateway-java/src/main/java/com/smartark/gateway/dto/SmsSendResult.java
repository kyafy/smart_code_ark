package com.smartark.gateway.dto;

public record SmsSendResult(
        String requestId,
        int expireIn
) {
}
