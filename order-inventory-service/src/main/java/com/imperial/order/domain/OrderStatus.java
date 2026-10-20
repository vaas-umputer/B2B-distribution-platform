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
    ROLLED_BACK
}
