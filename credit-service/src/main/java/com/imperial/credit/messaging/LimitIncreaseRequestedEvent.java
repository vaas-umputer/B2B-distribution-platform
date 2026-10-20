package com.imperial.credit.messaging;

import java.math.BigDecimal;
import java.util.UUID;

public record LimitIncreaseRequestedEvent(UUID eventId, UUID requestId, UUID tenantId, BigDecimal requestedAmount, String reason) {
}
