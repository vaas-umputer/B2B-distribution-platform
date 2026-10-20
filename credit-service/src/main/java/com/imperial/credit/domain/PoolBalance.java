package com.imperial.credit.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * No Persistable needed: this is Imperial's one singleton row, seeded once
 * by a Flyway migration and never INSERTed by the app - every save() here
 * follows a load (via the pessimistic-lock query), so the entity is always
 * already managed and merge() is the correct, cheap operation.
 */
@Entity
@Table(name = "pool_balance")
public class PoolBalance {

    @Id
    private Short id;

    private BigDecimal cash;
    private BigDecimal committedPayables;
    private BigDecimal operatingBuffer;
    private BigDecimal bufferPercent;
    private BigDecimal totalOutstanding;
    private int version;

    protected PoolBalance() {
        // for JPA
    }

    public Short getId() {
        return id;
    }

    public BigDecimal getCash() {
        return cash;
    }

    public BigDecimal getCommittedPayables() {
        return committedPayables;
    }

    public BigDecimal getOperatingBuffer() {
        return operatingBuffer;
    }

    public BigDecimal getBufferPercent() {
        return bufferPercent;
    }

    public BigDecimal getTotalOutstanding() {
        return totalOutstanding;
    }

    public void setTotalOutstanding(BigDecimal totalOutstanding) {
        this.totalOutstanding = totalOutstanding;
        this.version++;
    }

    public int getVersion() {
        return version;
    }
}
