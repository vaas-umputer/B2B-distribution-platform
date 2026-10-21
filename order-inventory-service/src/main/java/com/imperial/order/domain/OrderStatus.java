package com.imperial.order.domain;

/**
 * The saga's own state machine. PLACED is the row's state the instant it's
 * created, before any downstream call; everything else reflects how far the
 * saga got. CREDIT_REJECTED / STOCK_REJECTED / ROLLED_BACK are terminal
 * failure states - ROLLED_BACK is reserved for Phase 2's richer compensation
 * paths and unused so far (Phase 1 only needs the two rejection states,
 * since there's exactly one compensating action in this phase: releasing a
 * credit lock after a stock rejection).
 */
public enum OrderStatus {
    PLACED,
    CREDIT_LOCKED,
    STOCK_RESERVED,
    INVOICED,
    COMPLETED,
    CREDIT_REJECTED,
    STOCK_REJECTED,
    ROLLED_BACK,
    /**
     * A downstream Feign call (lockCredit or issueInvoice) threw instead of
     * returning a clean result - timeout, connection refused, a 5xx, or a
     * malformed response. Unlike CREDIT_REJECTED/STOCK_REJECTED (a clean
     * business decision), this means the saga genuinely doesn't know the
     * downstream outcome: a timed-out lockCredit call, for instance, may
     * have actually locked credit server-side with the caller never
     * learning the lock id needed to release it. Distinct terminal state so
     * the dealer/admin sees "something went wrong, contact support" rather
     * than a misleadingly clean rejection, and so these orders are easy to
     * find for manual reconciliation against Credit/Invoice Service.
     */
    FAILED
}
