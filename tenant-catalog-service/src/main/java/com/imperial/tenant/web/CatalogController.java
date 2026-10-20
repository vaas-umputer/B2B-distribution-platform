package com.imperial.tenant.web;

import com.imperial.common.tenant.TenantContext;
import com.imperial.tenant.dto.ProductDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/catalog")
public class CatalogController {

    // Phase 1 stub: shared catalog, no tenant/region pricing yet (Phase 2).
    private static final List<ProductDto> PRODUCTS = List.of(
            new ProductDto("SKU-001", "20L Drum - Industrial Lubricant", new BigDecimal("4500.00")),
            new ProductDto("SKU-002", "50kg Bag - Portland Cement", new BigDecimal("380.00")),
            new ProductDto("SKU-003", "1L Bottle - Surface Cleaner Concentrate", new BigDecimal("210.00")));

    @GetMapping("/{tenantId}")
    public List<ProductDto> getCatalog(@PathVariable UUID tenantId) {
        if (!tenantId.equals(TenantContext.get())) {
            throw new ResponseStatusException(FORBIDDEN, "cannot view another tenant's catalog");
        }
        return PRODUCTS;
    }
}
