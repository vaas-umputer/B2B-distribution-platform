package com.imperial.credit.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AlertView(UUID id, UUID tenantId, BigDecimal utilization, Instant createdAt) {
}
