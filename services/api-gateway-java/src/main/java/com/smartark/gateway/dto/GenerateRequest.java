package com.smartark.gateway.dto;

public record GenerateRequest(String projectId, String instructions, GenerateOptions options) {
    public GenerateRequest {
        if (options == null) {
            options = GenerateOptions.defaultOptions();
        }
    }
}
