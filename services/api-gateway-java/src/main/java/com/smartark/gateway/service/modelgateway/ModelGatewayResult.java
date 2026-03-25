package com.smartark.gateway.service.modelgateway;

public record ModelGatewayResult(
        String body,
        String upstreamRequestId,
        Integer httpStatus,
        Integer tokenInput,
        Integer tokenOutput,
        Integer tokenTotal
) {
}
