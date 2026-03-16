package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank(message = "项目名称不能为空")
        @Size(max = 100, message = "项目名称长度不能超过100")
        String name,
        @NotBlank(message = "项目描述不能为空")
        @Size(max = 1000, message = "项目描述长度不能超过1000")
        String description
) {
}
