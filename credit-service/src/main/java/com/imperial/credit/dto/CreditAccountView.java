package com.imperial.credit.dto;

import com.imperial.credit.domain.CreditAccount;

import java.math.BigDecimal;
import java.util.UUID;

public record CreditAccountView(UUID tenantId, BigDecimal creditLimit, BigDecimal outstanding, boolean throttled) {
    public static CreditAccountView from(CreditAccount account) {
        return new CreditAccountView(account.getTenantId(), account.getCreditLimit(), account.getOutstanding(), account.isThrottled());
    }
}
