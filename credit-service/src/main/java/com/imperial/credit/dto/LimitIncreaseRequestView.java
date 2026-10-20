package com.imperial.credit.dto;

import com.imperial.credit.domain.LimitIncreaseRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LimitIncreaseRequestView(
        UUID id, UUID tenantId, BigDecimal requestedAmount, String reason,
        String status, String reviewedBy, Instant reviewedAt, Instant createdAt) {

    public static LimitIncreaseRequestView from(LimitIncreaseRequest request) {
        return new LimitIncreaseRequestView(
                request.getId(), request.getTenantId(), request.getRequestedAmount(), request.getReason(),
                request.getStatus().name(), request.getReviewedBy(), request.getReviewedAt(), request.getCreatedAt());
    }
}
