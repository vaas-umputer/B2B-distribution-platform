package com.imperial.credit.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function tests, no Spring context, no database - exactly the Phase 2
 * exit criterion: "All formulas... covered by pure-function unit tests,
 * independent of the database."
 */
class CreditMathTest {

    @Test
    void availableWorkingCapital_subtractsPayablesAndBuffer() {
        var result = CreditMath.availableWorkingCapital(
                new BigDecimal("10000000"), new BigDecimal("500000"), new BigDecimal("1000000"));
        assertThat(result).isEqualTo(new BigDecimal("8500000"));
    }

    @Test
    void allocatablePool_appliesBufferPercent() {
        // 9,000,000 * (1 - 0.15) = 7,650,000
        var result = CreditMath.allocatablePool(new BigDecimal("9000000"), new BigDecimal("0.15"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("7650000.00"));
    }

    @Test
    void tenantAvailable_isLimitMinusOutstanding() {
        var result = CreditMath.tenantAvailable(new BigDecimal("500000"), new BigDecimal("120000"));
        assertThat(result).isEqualTo(new BigDecimal("380000"));
    }

    @Test
    void poolAvailable_isAllocatableMinusTotalOutstanding() {
        var result = CreditMath.poolAvailable(new BigDecimal("7650000"), new BigDecimal("2000000"));
        assertThat(result).isEqualTo(new BigDecimal("5650000"));
    }

    @Test
    void orderAllowed_requiresBothTenantAndPoolHeadroom() {
        // within tenant limit but NOT within pool - must still be rejected
        assertThat(CreditMath.orderAllowed(new BigDecimal("1000"),
                new BigDecimal("5000"), new BigDecimal("500"))).isFalse();

        // within pool but NOT within tenant limit - must still be rejected
        assertThat(CreditMath.orderAllowed(new BigDecimal("1000"),
                new BigDecimal("500"), new BigDecimal("5000"))).isFalse();

        // within both
        assertThat(CreditMath.orderAllowed(new BigDecimal("1000"),
                new BigDecimal("5000"), new BigDecimal("5000"))).isTrue();

        // exactly at the boundary - allowed (<=, not <)
        assertThat(CreditMath.orderAllowed(new BigDecimal("1000"),
                new BigDecimal("1000"), new BigDecimal("1000"))).isTrue();
    }

    @Test
    void newLimitSafeguard_neverCutsBelowCurrentOutstanding() {
        // recomputed limit is LOWER than what's currently owed - safeguard must win
        var result = CreditMath.applyNewLimitSafeguard(new BigDecimal("100000"), new BigDecimal("250000"));
        assertThat(result).isEqualTo(new BigDecimal("250000"));
    }

    @Test
    void newLimitSafeguard_usesRecomputedWhenItsHigher() {
        var result = CreditMath.applyNewLimitSafeguard(new BigDecimal("400000"), new BigDecimal("250000"));
        assertThat(result).isEqualTo(new BigDecimal("400000"));
    }

    @Test
    void overrideBelowOutstanding_isNotAllowed() {
        assertThat(CreditMath.isOverrideAllowed(new BigDecimal("100000"), new BigDecimal("250000"))).isFalse();
    }

    @Test
    void overrideAtOrAboveOutstanding_isAllowed() {
        assertThat(CreditMath.isOverrideAllowed(new BigDecimal("250000"), new BigDecimal("250000"))).isTrue();
        assertThat(CreditMath.isOverrideAllowed(new BigDecimal("400000"), new BigDecimal("250000"))).isTrue();
    }
}
