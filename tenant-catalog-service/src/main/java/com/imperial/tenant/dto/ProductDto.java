package com.imperial.tenant.dto;

import java.math.BigDecimal;

/**
 * Phase 1 stub: hardcoded, not tenant/region-priced yet. The real Mongo-backed
 * catalog with tenant/region pricing rules is built in Phase 2.
 */
public record ProductDto(String sku, String name, BigDecimal price) {
}
