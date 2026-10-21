package com.imperial.credit.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** What the admin actually typed in, after looking at a prior /risk-score suggestion - never applied automatically. */
public record ProvisionAccountRequest(@NotNull @PositiveOrZero BigDecimal creditLimit) {
}
