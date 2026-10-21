package com.imperial.credit.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record RiskScoreRequest(
        @NotNull @PositiveOrZero BigDecimal declaredMonthlyVolume,
        @PositiveOrZero int businessVintageYears,
        @NotNull @PositiveOrZero BigDecimal collateralOffered,
        @PositiveOrZero int relationshipCategoryPoints
) {
}
