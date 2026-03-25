package com.smartark.gateway.service.modelgateway;

import java.util.Map;

public record ModelGatewayInvocation(
        ModelGatewayEndpoint endpoint,
        String provider,
        String modelName,
        String baseUrl,
        String apiKey,
        Map<String, Object> payload,
        String bizScene,
        Integer timeoutMs
) {
    public boolean stream() {
        Object value = payload == null ? null : payload.get("stream");
        return value instanceof Boolean b && b;
    }
}
