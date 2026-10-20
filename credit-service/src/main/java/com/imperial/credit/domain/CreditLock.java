package com.imperial.credit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 1 stub version of what Phase 2 fleshes out into the full
 * credit_accounts / pool_balance / credit_locks schema. This table alone is
 * enough to make lockCredit/releaseCredit real (not just an in-memory
 * approve/reject), which is what the exit criteria actually require: a
 * forced stock failure must visibly flip a real row's status to RELEASED in
 * credit_db, not just return a 200 from a stateless endpoint.
 *
 * <p>Implements Persistable - see Tenant's Javadoc for why an assigned
 * (non-generated) UUID id needs this to avoid save() silently calling
 * merge() on a row that doesn't exist yet.
 */
@Entity
@Table(name = "credit_locks")
public class CreditLock implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "order_id")
    private UUID orderId;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private LockStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    protected CreditLock() {
        // for JPA
    }

    public CreditLock(UUID id, UUID tenantId, UUID orderId, BigDecimal amount, LockStatus status, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LockStatus getStatus() {
        return status;
    }

    public void setStatus(LockStatus status) {
        this.status = status;
    }
}
