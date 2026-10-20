package com.imperial.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * No Persistable needed: these rows are seeded once by a Flyway migration
 * and never INSERTed by the app - every save() here follows a load via the
 * pessimistic-lock query, so the entity is always already managed.
 */
@Entity
@Table(name = "inventory")
public class InventoryItem {

    @Id
    private String sku;

    private int qtyAvailable;
    private int qtyReserved;
    private int version;

    protected InventoryItem() {
        // for JPA
    }

    public String getSku() {
        return sku;
    }

    public int getQtyAvailable() {
        return qtyAvailable;
    }

    public int getQtyReserved() {
        return qtyReserved;
    }

    public void setQtyReserved(int qtyReserved) {
        this.qtyReserved = qtyReserved;
        this.version++;
    }

    public int getVersion() {
        return version;
    }
}
