package com.imperial.invoice.domain;

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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Implements Persistable - see Tenant's Javadoc (tenant-catalog-service) for
 * why an assigned (non-generated) UUID id needs this to avoid save()
 * silently calling merge() on a row that doesn't exist yet.
 */
@Entity
@Table(name = "invoices")
public class Invoice implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    private BigDecimal amount;

    private String terms;

    @Enumerated(EnumType.STRING)
    private InvoiceStatus status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "created_at")
    private Instant createdAt;

    protected Invoice() {
        // for JPA
    }

    public Invoice(UUID id, UUID orderId, UUID tenantId, BigDecimal amount, String terms,
                    InvoiceStatus status, LocalDate dueDate, Instant createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.tenantId = tenantId;
        this.amount = amount;
        this.terms = terms;
        this.status = status;
        this.dueDate = dueDate;
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

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getTerms() {
        return terms;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }
}
