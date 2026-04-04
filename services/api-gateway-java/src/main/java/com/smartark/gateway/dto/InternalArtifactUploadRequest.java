package com.smartark.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InternalArtifactUploadRequest(
        @JsonProperty("artifact_type") String artifactType,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("content_base64") String contentBase64
) {
}

