package com.imperial.credit.service;

import com.imperial.common.tenant.TenantScoped;
import com.imperial.credit.domain.LimitIncreaseRequest;
import com.imperial.credit.domain.LimitIncreaseRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A separate bean purely so its methods go through a real Spring proxy
 * (and therefore through TenantRlsAspect) when called from
 * LimitIncreaseRequestService - calling an @TenantScoped method on THIS
 * SAME class via self-invocation would silently skip the aspect, the same
 * self-invocation trap OrderStepService exists to avoid in
 * order-inventory-service.
 */
@Service
public class LimitIncreaseRequestStore {

    private final LimitIncreaseRequestRepository repository;

    public LimitIncreaseRequestStore(LimitIncreaseRequestRepository repository) {
        this.repository = repository;
    }

    @TenantScoped
    @Transactional
    public LimitIncreaseRequest create(UUID tenantId, BigDecimal requestedAmount, String reason) {
        var request = new LimitIncreaseRequest(UUID.randomUUID(), tenantId, requestedAmount, reason, Instant.now());
        return repository.save(request);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public Optional<LimitIncreaseRequest> find(UUID tenantId, UUID requestId) {
        return repository.findById(requestId);
    }

    @TenantScoped
    @Transactional
    public LimitIncreaseRequest save(UUID tenantId, LimitIncreaseRequest request) {
        return repository.save(request);
    }
}
