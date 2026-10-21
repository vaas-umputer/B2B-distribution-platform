package com.imperial.common.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Order & Inventory Service -> Credit Service: "reserve this much of this
 * tenant's credit for this order." Carried as the Feign call body in Phase 1's
 * stubbed check and Phase 2's real two-level (tenant + pool) check.
 */
public record LockCreditRequest(
        @NotNull UUID tenantId,
        @NotNull UUID orderId,
        @NotNull @Positive BigDecimal orderValue
) {
}
