package com.smartark.gateway.service.codegen;

import java.util.List;

public record CodegenRenderResult(
        boolean success,
        boolean invoked,
        String provider,
        String message,
        List<String> files
) {
    public static CodegenRenderResult notInvoked(String provider, String message) {
        return new CodegenRenderResult(false, false, provider, message, List.of());
    }

    public static CodegenRenderResult failed(String provider, String message) {
        return new CodegenRenderResult(false, true, provider, message, List.of());
    }
}

