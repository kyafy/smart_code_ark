package com.smartark.gateway.dto;

import java.util.List;

public record RequirementResult(
        String projectName,
        List<String> modules,
        List<String> techStack
) {
}
