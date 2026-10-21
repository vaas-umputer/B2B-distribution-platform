package com.imperial.credit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record ManualOverrideRequest(@NotNull @PositiveOrZero BigDecimal newLimit, @NotBlank String overrideReason) {
}
