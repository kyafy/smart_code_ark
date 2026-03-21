package com.smartark.gateway.dto;

import java.math.BigDecimal;

public record BalanceResult(BigDecimal balance, Integer quota) {
}
