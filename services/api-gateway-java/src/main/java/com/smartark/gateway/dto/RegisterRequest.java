package com.smartark.gateway.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Email(message = "邮箱格式不正确")
        @NotBlank(message = "邮箱不能为空")
        String email,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, message = "密码长度不能少于8位")
        String password,
        @NotBlank(message = "昵称不能为空")
        String nickname
) {
}
