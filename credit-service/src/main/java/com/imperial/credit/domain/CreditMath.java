package com.imperial.credit.domain;

import java.math.BigDecimal;

/**
 * Every formula from the plan's "Credit & Working Capital Algorithm"
 * section, as pure functions with no Spring/DB dependency - this is what
 * makes them unit-testable in isolation, which is the Phase 2 exit
 * criterion ("All formulas... covered by pure-function unit tests,
 * independent of the database").
 */
public final class CreditMath {

    private CreditMath() {
    }

    /** Level 1: AvailableWorkingCapital = Cash - CommittedToSupplierPayables - OperatingBuffer. */
    public static BigDecimal availableWorkingCapital(BigDecimal cash, BigDecimal committedPayables, BigDecimal operatingBuffer) {
        return cash.subtract(committedPayables).subtract(operatingBuffer);
    }

    /** AllocatablePool = AvailableWorkingCapital * (1 - BufferPercent). */
    public static BigDecimal allocatablePool(BigDecimal availableWorkingCapital, BigDecimal bufferPercent) {
        return availableWorkingCapital.multiply(BigDecimal.ONE.subtract(bufferPercent));
    }

    /** Level 2/order-time: TenantAvailable = TenantLimit - CurrentOutstandingInvoices(tenant). */
    public static BigDecimal tenantAvailable(BigDecimal creditLimit, BigDecimal outstanding) {
        return creditLimit.subtract(outstanding);
    }

    /** PoolAvailable = AllocatablePool - sum(CurrentOutstandingInvoices(all tenants)). */
    public static BigDecimal poolAvailable(BigDecimal allocatablePool, BigDecimal totalOutstanding) {
        return allocatablePool.subtract(totalOutstanding);
    }

    /** OrderAllowed = OrderValue <= min(TenantAvailable, PoolAvailable). Both checks must hold. */
    public static boolean orderAllowed(BigDecimal orderValue, BigDecimal tenantAvailable, BigDecimal poolAvailable) {
        return orderValue.compareTo(tenantAvailable.min(poolAvailable)) <= 0;
    }

    /**
     * The plan's explicit safeguard for any recompute/override path: a
     * recalculated limit must never be allowed to cut below what the
     * tenant already legitimately owes - that would make an existing,
     * already-approved balance look like an instant breach.
     */
    public static BigDecimal applyNewLimitSafeguard(BigDecimal recomputedLimit, BigDecimal currentOutstanding) {
        return recomputedLimit.max(currentOutstanding);
    }

    /**
     * Phase 3's manual-override/limit-increase-approval safeguard is
     * phrased differently from recompute's: the plan's exit criteria for
     * this path says such an override must be REJECTED outright, "not
     * silently adjusted" - unlike recompute, which clamps. Same underlying
     * invariant (never let a limit sit below what's already owed), two
     * different error-handling shapes for two different call sites.
     */
    public static boolean isOverrideAllowed(BigDecimal newLimit, BigDecimal currentOutstanding) {
        return newLimit.compareTo(currentOutstanding) >= 0;
    }
}
