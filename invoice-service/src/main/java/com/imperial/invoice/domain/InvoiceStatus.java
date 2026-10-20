package com.imperial.invoice.domain;

/**
 * Only ISSUED is reachable in Phase 1 - no further transitions, no
 * RabbitMQ events yet. The full ISSUED -> PAID/OVERDUE -> CLOSED state
 * machine with an explicit allowed-transition map is Phase 2.
 */
public enum InvoiceStatus {
    ISSUED,
    PAID,
    OVERDUE,
    CLOSED
}
