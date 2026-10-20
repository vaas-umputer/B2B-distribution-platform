package com.imperial.credit.domain;

public enum LockStatus {
    LOCKED,
    /** Compensated: credit was approved but the order never shipped (e.g. stock rejection). */
    RELEASED,
    /** The normal happy path: the invoice this lock backed was paid, rotating the capacity back. */
    CONSUMED
}
