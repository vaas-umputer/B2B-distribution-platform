package com.imperial.credit.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Implements the plan's onboarding rubric exactly as tabulated (declared
 * volume 0-3, vintage 0-2, collateral 0-3, relationship 0-2 => a 0-10
 * score) and its RiskMultiplier bands (0.5x low score to 2.0x high score).
 * The plan gives the point RANGES and the multiplier RANGE but not exact
 * cutoffs within them - the bands below are this implementation's concrete,
 * reasonable choice for where those cutoffs fall, not a value spelled out
 * in the plan itself.
 *
 * <p>No Spring/DB dependency beyond @Component for DI convenience - every
 * method here is callable directly in a plain unit test, which is the
 * whole point: these are exactly the formulas the Phase 2 exit criteria
 * require to be "covered by pure-function unit tests, independent of the
 * database."
 *
 * <p>This NEVER sets a tenant's actual credit limit by itself - the plan is
 * explicit that the score is "an admin-facing recommendation, never a
 * binding number." Something else (CreditCheckService.provisionAccount)
 * is what an admin calls after looking at this result.
 */
@Component
public class RiskScoringService {

    private final BigDecimal newAgentCeiling;

    public RiskScoringService(@Value("${credit.new-agent-ceiling:1500000}") BigDecimal newAgentCeiling) {
        this.newAgentCeiling = newAgentCeiling;
    }

    public RiskScoreResult computeScore(OnboardingData data) {
        int volumePoints = volumePoints(data.declaredMonthlyVolume());
        int vintagePoints = vintagePoints(data.businessVintageYears());
        int collateralPoints = collateralPoints(data.collateralOffered());
        int relationshipPoints = clamp(data.relationshipCategoryPoints(), 0, 2);

        int score = volumePoints + vintagePoints + collateralPoints + relationshipPoints;
        BigDecimal multiplier = riskMultiplier(score);
        BigDecimal suggested = data.declaredMonthlyVolume().multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cappedForNewAgent = suggested.min(newAgentCeiling);

        return new RiskScoreResult(score, multiplier, suggested, cappedForNewAgent);
    }

    /** 0-3 points. */
    private int volumePoints(BigDecimal declaredMonthlyVolume) {
        if (declaredMonthlyVolume.compareTo(new BigDecimal("1000000")) >= 0) return 3;
        if (declaredMonthlyVolume.compareTo(new BigDecimal("500000")) >= 0) return 2;
        if (declaredMonthlyVolume.compareTo(new BigDecimal("100000")) >= 0) return 1;
        return 0;
    }

    /** 0-2 points. */
    private int vintagePoints(int years) {
        if (years >= 5) return 2;
        if (years >= 2) return 1;
        return 0;
    }

    /** 0-3 points. */
    private int collateralPoints(BigDecimal collateral) {
        if (collateral.compareTo(new BigDecimal("500000")) >= 0) return 3;
        if (collateral.compareTo(new BigDecimal("100000")) >= 0) return 2;
        if (collateral.signum() > 0) return 1;
        return 0;
    }

    /** Score range is 0-10; multiplier bands span the plan's stated 0.5x-2.0x. */
    private BigDecimal riskMultiplier(int score) {
        if (score >= 9) return new BigDecimal("2.0");
        if (score >= 7) return new BigDecimal("1.5");
        if (score >= 5) return new BigDecimal("1.0");
        if (score >= 3) return new BigDecimal("0.75");
        return new BigDecimal("0.5");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
