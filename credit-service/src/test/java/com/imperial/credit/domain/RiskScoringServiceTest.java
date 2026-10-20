package com.imperial.credit.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No Spring context needed - constructed directly, exactly like any other
 * pure function. The exact point-band cutoffs are this implementation's own
 * concrete choice (the plan gives ranges, not exact numbers - see the
 * class's own Javadoc), so these tests pin down THIS implementation's
 * behavior at each band boundary.
 */
class RiskScoringServiceTest {

    private final RiskScoringService service = new RiskScoringService(new BigDecimal("1500000"));

    @ParameterizedTest
    @CsvSource({
            // declaredVolume, vintageYears, collateral, relationshipPoints, expectedScore
            "50000,   0,      0,       0, 0",   // bottom of every band
            "1000000, 5,      500000,  2, 10",  // top of every band
            "500000,  2,      100000,  1, 6",   // mid-band mix
    })
    void computeScore_sumsAllFourFactors(String volume, int vintage, String collateral, int relationship, int expectedScore) {
        var data = new OnboardingData(new BigDecimal(volume), vintage, new BigDecimal(collateral), relationship);
        assertThat(service.computeScore(data).score()).isEqualTo(expectedScore);
    }

    @Test
    void lowestScore_getsLowestMultiplier() {
        var data = new OnboardingData(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0);
        var result = service.computeScore(data);
        assertThat(result.riskMultiplier()).isEqualByComparingTo("0.5");
    }

    @Test
    void highestScore_getsHighestMultiplier() {
        var data = new OnboardingData(new BigDecimal("1000000"), 5, new BigDecimal("500000"), 2);
        var result = service.computeScore(data);
        assertThat(result.score()).isEqualTo(10);
        assertThat(result.riskMultiplier()).isEqualByComparingTo("2.0");
    }

    @Test
    void suggestedLimit_isDeclaredVolumeTimesMultiplier() {
        var data = new OnboardingData(new BigDecimal("1000000"), 5, new BigDecimal("500000"), 2);
        var result = service.computeScore(data);
        assertThat(result.suggestedLimit()).isEqualByComparingTo("2000000.00"); // 1,000,000 * 2.0
    }

    @Test
    void newAgentCeiling_capsAHighSuggestion() {
        var data = new OnboardingData(new BigDecimal("1000000"), 5, new BigDecimal("500000"), 2);
        var result = service.computeScore(data); // suggests 2,000,000, ceiling is 1,500,000
        assertThat(result.suggestedLimitAfterCeiling()).isEqualByComparingTo("1500000");
    }

    @Test
    void newAgentCeiling_doesNotRaiseALowSuggestion() {
        var data = new OnboardingData(new BigDecimal("100000"), 0, BigDecimal.ZERO, 0); // low score, low volume
        var result = service.computeScore(data);
        assertThat(result.suggestedLimitAfterCeiling()).isEqualByComparingTo(result.suggestedLimit());
        assertThat(result.suggestedLimitAfterCeiling()).isLessThan(new BigDecimal("1500000"));
    }

    @Test
    void relationshipCategoryPoints_isClampedToZeroToTwo() {
        var tooHigh = new OnboardingData(BigDecimal.ZERO, 0, BigDecimal.ZERO, 99);
        var tooLow = new OnboardingData(BigDecimal.ZERO, 0, BigDecimal.ZERO, -5);
        assertThat(service.computeScore(tooHigh).score()).isEqualTo(2);
        assertThat(service.computeScore(tooLow).score()).isEqualTo(0);
    }
}
