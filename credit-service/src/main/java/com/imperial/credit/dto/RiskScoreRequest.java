package com.imperial.credit.dto;

import java.math.BigDecimal;

public record RiskScoreRequest(
        BigDecimal declaredMonthlyVolume,
        int businessVintageYears,
        BigDecimal collateralOffered,
        int relationshipCategoryPoints
) {
}
