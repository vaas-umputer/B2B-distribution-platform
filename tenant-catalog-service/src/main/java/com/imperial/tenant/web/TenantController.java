package com.imperial.tenant.web;

import com.imperial.common.tenant.TenantContext;
import com.imperial.tenant.domain.Tenant;
import com.imperial.tenant.dto.OnboardTenantRequest;
import com.imperial.tenant.dto.TenantView;
import com.imperial.tenant.service.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    public ResponseEntity<TenantView> onboard(@RequestBody OnboardTenantRequest request) {
        try {
            Tenant tenant = tenantService.onboard(request.name(), request.username(), request.password(), request.email(), request.phone());
            return ResponseEntity.status(HttpStatus.CREATED).body(TenantView.from(tenant));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public TenantView getById(@PathVariable UUID id) {
        UUID callerTenantId = TenantContext.get();
        if (!id.equals(callerTenantId)) {
            // Belt-and-suspenders on top of RLS: RLS would already return
            // nothing for a mismatched id, but failing fast here gives a
            // clear 403 instead of an ambiguous 404.
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "cannot access another tenant's record");
        }
        return tenantService.findById(id)
                .map(TenantView::from)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }
}
