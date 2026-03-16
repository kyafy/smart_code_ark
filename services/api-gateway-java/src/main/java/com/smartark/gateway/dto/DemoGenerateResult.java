package com.smartark.gateway.dto;

public record DemoGenerateResult(
        RequirementResult requirementAnalysis,
        GenerateResult generationResult
) {
}
