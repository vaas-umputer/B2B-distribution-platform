package com.imperial.credit.domain;

import java.math.BigDecimal;

public record RiskScoreResult(
        int score,
        BigDecimal riskMultiplier,
        BigDecimal suggestedLimit,
        BigDecimal suggestedLimitAfterCeiling
) {
}
