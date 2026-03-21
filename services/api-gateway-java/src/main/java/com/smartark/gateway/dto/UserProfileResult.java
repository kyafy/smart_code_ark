package com.smartark.gateway.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record UserProfileResult(
        Long userId,
        String username,
        BigDecimal balance,
        Integer quota,
        LocalDateTime createdAt
) {
}
