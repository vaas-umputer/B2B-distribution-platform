package com.imperial.credit.domain;

import jakarta.persistence.Entity;
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
 * One current score per tenant - a later call just overwrites the previous
 * one (save() upserts by tenant_id). What gets recomputed on
 * POST /credit/{tenantId}/recompute reads these stored onboarding inputs
 * back, which is the whole reason this is a table and not just a stateless
 * calculation.
 */
@Entity
@Table(name = "credit_risk_scores")
public class CreditRiskScore implements Persistable<UUID> {

    @Id
    private UUID tenantId;

    @Transient
    private boolean isNew = true;

    private BigDecimal declaredVolume;
    private int businessVintage;
    private BigDecimal collateral;
    private int relationshipCategory;
    private int computedScore;
    private BigDecimal suggestedLimit;
    private Instant createdAt;

    protected CreditRiskScore() {
        // for JPA
    }

    public CreditRiskScore(UUID tenantId, BigDecimal declaredVolume, int businessVintage, BigDecimal collateral,
                            int relationshipCategory, int computedScore, BigDecimal suggestedLimit, Instant createdAt) {
        this.tenantId = tenantId;
        this.declaredVolume = declaredVolume;
        this.businessVintage = businessVintage;
        this.collateral = collateral;
        this.relationshipCategory = relationshipCategory;
        this.computedScore = computedScore;
        this.suggestedLimit = suggestedLimit;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return tenantId;
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

    public BigDecimal getDeclaredVolume() {
        return declaredVolume;
    }

    public int getBusinessVintage() {
        return businessVintage;
    }

    public BigDecimal getCollateral() {
        return collateral;
    }

    public int getRelationshipCategory() {
        return relationshipCategory;
    }

    public int getComputedScore() {
        return computedScore;
    }

    public BigDecimal getSuggestedLimit() {
        return suggestedLimit;
    }

    /**
     * For the upsert-by-tenant path: load the existing row (if any) via
     * findById first, then call these setters on THAT managed instance and
     * save() it - never construct a new CreditRiskScore for a tenant that
     * might already have one, since a fresh instance's isNew defaults to
     * true and save() would try to INSERT a duplicate primary key.
     */
    public void update(BigDecimal declaredVolume, int businessVintage, BigDecimal collateral,
                        int relationshipCategory, int computedScore, BigDecimal suggestedLimit, Instant createdAt) {
        this.declaredVolume = declaredVolume;
        this.businessVintage = businessVintage;
        this.collateral = collateral;
        this.relationshipCategory = relationshipCategory;
        this.computedScore = computedScore;
        this.suggestedLimit = suggestedLimit;
        this.createdAt = createdAt;
    }
}
