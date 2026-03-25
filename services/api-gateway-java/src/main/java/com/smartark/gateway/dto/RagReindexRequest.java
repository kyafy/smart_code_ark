package com.smartark.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request payload for rebuilding RAG index for a paper session.")
public record RagReindexRequest(
        @Schema(description = "Paper topic session ID.", example = "123")
        Long sessionId
) {
}
