package com.imperial.credit.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record SubmitLimitIncreaseRequest(@NotNull @Positive BigDecimal requestedAmount, String reason) {
}
