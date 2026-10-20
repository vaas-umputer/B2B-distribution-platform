package com.imperial.invoice.domain;

/**
 * The plan's explicit allowed-transition map: ISSUED->PAID, ISSUED->OVERDUE,
 * OVERDUE->PAID, *->CLOSED. A pure, DB-independent function - the whole
 * point is that an illegal transition returns 409 and never silently
 * succeeds, which only works if "is this legal" is checked somewhere
 * explicit rather than just always overwriting the status column.
 */
public final class InvoiceTransitions {

    private InvoiceTransitions() {
    }

    public static boolean isAllowed(InvoiceStatus from, InvoiceStatus to) {
        if (to == InvoiceStatus.CLOSED) {
            return true; // *->CLOSED
        }
        return switch (from) {
            case ISSUED -> to == InvoiceStatus.PAID || to == InvoiceStatus.OVERDUE;
            case OVERDUE -> to == InvoiceStatus.PAID;
            default -> false;
        };
    }
}
