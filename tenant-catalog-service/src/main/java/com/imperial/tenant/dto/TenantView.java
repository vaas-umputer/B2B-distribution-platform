package com.imperial.tenant.dto;

import com.imperial.tenant.domain.Tenant;

import java.math.BigDecimal;
import java.util.UUID;

public record TenantView(UUID id, String name, String status, BigDecimal creditLimit, Integer riskScore) {
    public static TenantView from(Tenant tenant) {
        return new TenantView(
                tenant.getId(),
                tenant.getName(),
                tenant.getStatus().name(),
                tenant.getCreditLimit(),
                tenant.getRiskScore());
    }
}
