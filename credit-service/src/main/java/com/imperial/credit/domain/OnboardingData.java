package com.imperial.credit.domain;

import java.math.BigDecimal;

/**
 * The inputs the plan's onboarding rubric scores: declared monthly volume,
 * business vintage in years, collateral/security deposit offered, and a
 * relationship/priority category already expressed directly as 0-2 points
 * (the plan treats that factor as a category→points lookup an admin picks,
 * not something computed from a finer-grained input).
 */
public record OnboardingData(
        BigDecimal declaredMonthlyVolume,
        int businessVintageYears,
        BigDecimal collateralOffered,
        int relationshipCategoryPoints
) {
}
