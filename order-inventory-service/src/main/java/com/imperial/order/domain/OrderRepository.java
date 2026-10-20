package com.imperial.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    /** The whole of request-level idempotency: a retried POST /orders with the same key finds its own earlier result here. */
    Optional<OrderEntity> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    List<OrderEntity> findAllByOrderByCreatedAtDesc();
}
