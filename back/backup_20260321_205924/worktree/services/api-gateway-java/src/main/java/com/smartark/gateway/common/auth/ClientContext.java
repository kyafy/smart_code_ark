package com.smartark.gateway.common.auth;

public record ClientContext(
        String platform,
        String appVersion,
        String deviceId
) {
}
