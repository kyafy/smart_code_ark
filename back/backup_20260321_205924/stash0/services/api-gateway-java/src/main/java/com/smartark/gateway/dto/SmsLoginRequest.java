package com.smartark.gateway.dto;

import jakarta.validation.constraints.NotBlank;

public record SmsLoginRequest(
        @NotBlank String phone,
        @NotBlank String captcha
) {
}
