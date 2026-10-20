package com.imperial.credit.service;

import com.imperial.common.tenant.TenantContext;
import com.imperial.credit.domain.LimitIncreaseRequest;
import com.imperial.credit.domain.LimitIncreaseStatus;
import com.imperial.credit.dto.LimitIncreaseRequestView;
import com.imperial.credit.messaging.LimitIncreaseRequestedPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The self-service limit-increase queue: a tenant submits, an admin
 * approves or rejects. Approval and manual override (CreditController's
 * PATCH /credit/{tenantId}/limit) both fund through
 * CreditCheckService.overrideLimit - "one audit trail for both paths," per
 * the plan.
 *
 * <p>Listing for admins has to see every tenant's requests at once, which
 * limit_increase_requests' own RLS policy (from Phase 2) deliberately
 * doesn't allow for a normal app_user connection - so that one read goes
 * through systemJdbcTemplate (svc_system, BYPASSRLS), the same narrow
 * exception the scheduled jobs use, and ONLY for that one cross-tenant
 * read. Approving/rejecting a SPECIFIC request still goes through the
 * normal tenant-scoped path once this class has looked up (via the same
 * privileged connection) which tenant that request belongs to.
 */
@Service
public class LimitIncreaseRequestService {

    private static final Logger log = LoggerFactory.getLogger(LimitIncreaseRequestService.class);

    private final LimitIncreaseRequestStore store;
    private final CreditCheckService creditCheckService;
    private final LimitIncreaseRequestedPublisher publisher;
    private final JdbcTemplate systemJdbcTemplate;

    public LimitIncreaseRequestService(LimitIncreaseRequestStore store,
                                        CreditCheckService creditCheckService,
                                        LimitIncreaseRequestedPublisher publisher,
                                        @Qualifier("systemJdbcTemplate") JdbcTemplate systemJdbcTemplate) {
        this.store = store;
        this.creditCheckService = creditCheckService;
        this.publisher = publisher;
        this.systemJdbcTemplate = systemJdbcTemplate;
    }

    public LimitIncreaseRequest submit(UUID tenantId, BigDecimal requestedAmount, String reason) {
        LimitIncreaseRequest request = store.create(tenantId, requestedAmount, reason);
        publisher.publish(request);
        log.info("limit increase requested: tenant {} wants {} (requestId={})", tenantId, requestedAmount, request.getId());
        return request;
    }

    public List<LimitIncreaseRequestView> listForAdmin(LimitIncreaseStatus statusFilter) {
        String sql = "SELECT id, tenant_id, requested_amount, reason, status, reviewed_by, reviewed_at, created_at " +
                "FROM limit_increase_requests" + (statusFilter != null ? " WHERE status = ?" : "") +
                " ORDER BY created_at DESC";
        var rowMapper = (org.springframework.jdbc.core.RowMapper<LimitIncreaseRequestView>) (rs, i) -> new LimitIncreaseRequestView(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getBigDecimal("requested_amount"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getString("reviewed_by"),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getTimestamp("created_at").toInstant());
        return statusFilter != null
                ? systemJdbcTemplate.query(sql, rowMapper, statusFilter.name())
                : systemJdbcTemplate.query(sql, rowMapper);
    }

    /** Applied as an override, so it shares the exact same audit trail and below-outstanding safeguard as a manual override. */
    public LimitIncreaseRequest approve(UUID requestId, String reviewerId) {
        UUID tenantId = lookupTenantId(requestId);
        TenantContext.set(tenantId);
        try {
            LimitIncreaseRequest request = store.find(tenantId, requestId)
                    .orElseThrow(() -> new NoSuchElementException("limit increase request not found: " + requestId));
            requirePending(request);

            creditCheckService.overrideLimit(tenantId, request.getRequestedAmount(),
                    "limit increase request approved: " + request.getReason(), reviewerId);

            request.markReviewed(LimitIncreaseStatus.APPROVED, reviewerId, Instant.now());
            return store.save(tenantId, request);
        } finally {
            TenantContext.clear();
        }
    }

    public LimitIncreaseRequest reject(UUID requestId, String reviewerId) {
        UUID tenantId = lookupTenantId(requestId);
        TenantContext.set(tenantId);
        try {
            LimitIncreaseRequest request = store.find(tenantId, requestId)
                    .orElseThrow(() -> new NoSuchElementException("limit increase request not found: " + requestId));
            requirePending(request);

            request.markReviewed(LimitIncreaseStatus.REJECTED, reviewerId, Instant.now());
            return store.save(tenantId, request);
        } finally {
            TenantContext.clear();
        }
    }

    private void requirePending(LimitIncreaseRequest request) {
        if (request.getStatus() != LimitIncreaseStatus.PENDING) {
            throw new IllegalStateException("request " + request.getId() + " was already reviewed (" + request.getStatus() + ")");
        }
    }

    private UUID lookupTenantId(UUID requestId) {
        try {
            return systemJdbcTemplate.queryForObject(
                    "SELECT tenant_id FROM limit_increase_requests WHERE id = ?", UUID.class, requestId);
        } catch (EmptyResultDataAccessException e) {
            throw new NoSuchElementException("limit increase request not found: " + requestId);
        }
    }
}
