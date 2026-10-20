package com.imperial.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderView(UUID id, String status, BigDecimal totalValue, UUID invoiceId, String rejectionReason) {
}
