package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotBlank;

public record DemoGenerateRequest(
        @NotBlank(message = "需求描述不能为空")
        String requirement
) {
}
