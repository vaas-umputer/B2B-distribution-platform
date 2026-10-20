package com.imperial.common.dto;

import java.util.UUID;

/**
 * Credit Service's answer to a LockCreditRequest. When approved is false,
 * lockId is null and Order & Inventory Service moves the order straight to
 * CREDIT_REJECTED - there is nothing yet to compensate.
 */
public record LockCreditResponse(
        boolean approved,
        UUID lockId,
        String reason
) {
    public static LockCreditResponse approved(UUID lockId) {
        return new LockCreditResponse(true, lockId, null);
    }

    public static LockCreditResponse rejected(String reason) {
        return new LockCreditResponse(false, null, reason);
    }
}
