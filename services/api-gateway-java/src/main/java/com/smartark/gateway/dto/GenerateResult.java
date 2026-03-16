package com.smartark.gateway.dto;

import java.util.List;

public record GenerateResult(
        String summary,
        List<String> generatedFiles
) {
}
