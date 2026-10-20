package com.imperial.credit.dto;

import com.imperial.credit.domain.RiskScoreResult;

import java.math.BigDecimal;

public record RiskScoreView(int score, BigDecimal riskMultiplier, BigDecimal suggestedLimit, BigDecimal suggestedLimitAfterCeiling) {
    public static RiskScoreView from(RiskScoreResult result) {
        return new RiskScoreView(result.score(), result.riskMultiplier(), result.suggestedLimit(), result.suggestedLimitAfterCeiling());
    }
}
