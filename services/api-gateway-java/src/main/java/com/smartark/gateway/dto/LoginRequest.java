package com.smartark.gateway.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Email(message = "邮箱格式不正确")
        @NotBlank(message = "邮箱不能为空")
        String email,
        @NotBlank(message = "密码不能为空")
        String password
) {
}
