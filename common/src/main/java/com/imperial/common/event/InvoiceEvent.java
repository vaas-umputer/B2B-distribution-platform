package com.imperial.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payload Invoice Service publishes to RabbitMQ's invoice.exchange (routing
 * keys invoice.paid / invoice.overdue, wired in Phase 2) and Credit Service
 * consumes to release or throttle credit - the asynchronous half of the saga.
 * eventId is the consumer-side idempotency key: a redelivered message must
 * never release or throttle credit twice.
 */
public record InvoiceEvent(
        UUID eventId,
        UUID invoiceId,
        UUID orderId,
        UUID tenantId,
        BigDecimal amount
) {
}
