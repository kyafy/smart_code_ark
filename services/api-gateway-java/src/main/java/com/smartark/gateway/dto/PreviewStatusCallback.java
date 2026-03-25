package com.smartark.gateway.dto;

public record PreviewStatusCallback(
        String status,
        String phase,
        String previewUrl,
        String error,
        Integer errorCode
) {
}
