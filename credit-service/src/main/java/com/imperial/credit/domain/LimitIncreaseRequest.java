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
 * Implements Persistable - see Tenant's Javadoc (tenant-catalog-service) for
 * why an assigned (non-generated) UUID id needs this. This entity is a
 * genuine example of the OTHER direction that matters: a row created once
 * (isNew=true) and LATER loaded-and-mutated (findById -> isNew=false via
 * @PostLoad -> correctly uses merge/update, not a duplicate insert) when
 * approved or rejected.
 */
@Entity
@Table(name = "limit_increase_requests")
public class LimitIncreaseRequest implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "requested_amount")
    private BigDecimal requestedAmount;

    private String reason;

    @Enumerated(EnumType.STRING)
    private LimitIncreaseStatus status;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    protected LimitIncreaseRequest() {
        // for JPA
    }

    public LimitIncreaseRequest(UUID id, UUID tenantId, BigDecimal requestedAmount, String reason, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.requestedAmount = requestedAmount;
        this.reason = reason;
        this.status = LimitIncreaseStatus.PENDING;
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

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public String getReason() {
        return reason;
    }

    public LimitIncreaseStatus getStatus() {
        return status;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markReviewed(LimitIncreaseStatus status, String reviewedBy, Instant reviewedAt) {
        this.status = status;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
    }
}
