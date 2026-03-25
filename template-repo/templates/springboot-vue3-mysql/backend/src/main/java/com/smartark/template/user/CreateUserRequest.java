package com.smartark.template.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Email @Size(max = 128) String email
) {
}
