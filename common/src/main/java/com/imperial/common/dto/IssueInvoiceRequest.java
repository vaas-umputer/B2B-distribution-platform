package com.imperial.common.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Order & Inventory Service -> Invoice Service, the last synchronous hop in
 * the saga: the order has cleared credit and stock, so an invoice can be
 * issued against it. "terms" is the payment window, e.g. "NET_30".
 */
public record IssueInvoiceRequest(
        @NotNull UUID orderId,
        @NotNull UUID tenantId,
        @NotNull @Positive BigDecimal amount,
        String terms
) {
}
