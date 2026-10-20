package com.imperial.credit.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Implements Persistable - see Tenant's Javadoc (tenant-catalog-service) for
 * why an assigned (non-generated) UUID id needs this.
 */
@Entity
@Table(name = "credit_accounts")
public class CreditAccount implements Persistable<UUID> {

    @Id
    private UUID tenantId;

    @Transient
    private boolean isNew = true;

    private BigDecimal creditLimit;
    private BigDecimal outstanding;
    private boolean throttled;
    private int version;

    protected CreditAccount() {
        // for JPA
    }

    public CreditAccount(UUID tenantId, BigDecimal creditLimit) {
        this.tenantId = tenantId;
        this.creditLimit = creditLimit;
        this.outstanding = BigDecimal.ZERO;
        this.throttled = false;
        this.version = 0;
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

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
        this.version++;
    }

    public BigDecimal getOutstanding() {
        return outstanding;
    }

    public void setOutstanding(BigDecimal outstanding) {
        this.outstanding = outstanding;
        this.version++;
    }

    public boolean isThrottled() {
        return throttled;
    }

    public void setThrottled(boolean throttled) {
        this.throttled = throttled;
        this.version++;
    }

    public int getVersion() {
        return version;
    }
}
