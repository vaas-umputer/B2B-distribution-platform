package com.imperial.credit.service;

import java.math.BigDecimal;

/** Thrown when a manual override/approval would cut a limit below what the tenant currently owes - rejected outright, never clamped. */
public class OverrideBelowOutstandingException extends RuntimeException {

    public OverrideBelowOutstandingException(BigDecimal newLimit, BigDecimal currentOutstanding) {
        super(String.format("newLimit %s is below current outstanding exposure %s - rejected, not adjusted", newLimit, currentOutstanding));
    }
}
