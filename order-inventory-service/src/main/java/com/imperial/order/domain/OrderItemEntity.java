package com.imperial.order.domain;

import jakarta.persistence.Column;
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
 * A flat entity with its own surrogate id and a plain order_id column -
 * deliberately not a bidirectional JPA @OneToMany/@ManyToOne association.
 * Every other relationship in this codebase is managed this way (explicit
 * repository calls, not object-graph navigation), and introducing the one
 * JPA association type here would be inconsistent for no real benefit at
 * this scale.
 */
@Entity
@Table(name = "order_items")
public class OrderItemEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "order_id")
    private UUID orderId;

    private String sku;
    private int qty;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    protected OrderItemEntity() {
        // for JPA
    }

    public OrderItemEntity(UUID id, UUID orderId, String sku, int qty, BigDecimal unitPrice) {
        this.id = id;
        this.orderId = orderId;
        this.sku = sku;
        this.qty = qty;
        this.unitPrice = unitPrice;
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

    public String getSku() {
        return sku;
    }

    public int getQty() {
        return qty;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
