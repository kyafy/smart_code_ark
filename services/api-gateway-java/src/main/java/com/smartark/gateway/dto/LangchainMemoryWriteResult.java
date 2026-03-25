package com.smartark.gateway.dto;

public record LangchainMemoryWriteResult(
        boolean written,
        String recordId
) {
}
