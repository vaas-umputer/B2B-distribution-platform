package com.imperial.order.service;

import com.imperial.common.tenant.TenantScoped;
import com.imperial.order.domain.*;
import com.imperial.order.dto.OrderLineRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Each public method here is its OWN transaction, called from
 * OrderSagaOrchestrator across a real Spring-proxy bean boundary (not
 * self-invocation, which would silently skip the @Transactional advice
 * entirely). That's deliberate: the orchestrator itself is NOT
 * transactional, so every step commits durably the instant it returns,
 * before the orchestrator makes its next network call. Phase 1 wrapped the
 * whole saga in one transaction - fine for proving the pattern, but it
 * meant nothing was actually durable until the very end. This is the
 * concrete change the plan's Phase 2 description asks for: "now persisting
 * saga_state after every step... so a crash mid-saga is inspectable."
 */
@Service
public class OrderStepService {

    private static final Logger log = LoggerFactory.getLogger(OrderStepService.class);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryRepository inventoryRepository;

    public OrderStepService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                             InventoryRepository inventoryRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public Optional<OrderEntity> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return orderRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
    }

    @TenantScoped
    @Transactional
    public OrderEntity createOrder(UUID tenantId, List<OrderLineRequest> items, BigDecimal totalValue, String idempotencyKey) {
        OrderEntity order = new OrderEntity(UUID.randomUUID(), tenantId, OrderStatus.PLACED, totalValue, idempotencyKey, Instant.now());
        orderRepository.save(order);

        for (OrderLineRequest item : items) {
            orderItemRepository.save(new OrderItemEntity(UUID.randomUUID(), order.getId(), item.sku(), item.qty(), item.unitPrice()));
        }

        log.info("order {} PLACED for tenant {} - totalValue={}", order.getId(), tenantId, totalValue);
        return order;
    }

    @TenantScoped
    @Transactional
    public OrderEntity recordCreditResult(UUID orderId, boolean approved, String rejectionReason) {
        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        if (approved) {
            order.setSagaState(OrderStatus.CREDIT_LOCKED);
        } else {
            order.setStatus(OrderStatus.CREDIT_REJECTED);
            order.setSagaState(OrderStatus.CREDIT_REJECTED);
            order.setRejectionReason(rejectionReason);
            log.info("order {} CREDIT_REJECTED: {}", orderId, rejectionReason);
        }
        orderRepository.save(order);
        return order;
    }

    /**
     * Real check-then-commit, replacing Phase 1's always-available stub.
     * Every sku touched is locked in a fixed, alphabetical order first -
     * see InventoryRepository's Javadoc for why that ordering matters.
     */
    @TenantScoped
    @Transactional
    public StockReservationResult reserveStock(UUID orderId, List<OrderLineRequest> items, boolean simulateFailure) {
        OrderEntity order = orderRepository.findById(orderId).orElseThrow();

        if (simulateFailure) {
            String reason = "stock unavailable (simulated via simulateStockFailure=true)";
            order.setStatus(OrderStatus.STOCK_REJECTED);
            order.setSagaState(OrderStatus.STOCK_REJECTED);
            order.setRejectionReason(reason);
            orderRepository.save(order);
            return StockReservationResult.failed(reason);
        }

        var sortedSkus = new TreeSet<String>();
        items.forEach(item -> sortedSkus.add(item.sku()));

        List<InventoryItem> locked = new ArrayList<>();
        for (String sku : sortedSkus) {
            InventoryItem inv = inventoryRepository.lockBySku(sku).orElse(null);
            if (inv == null) {
                String reason = "unknown sku: " + sku;
                order.setStatus(OrderStatus.STOCK_REJECTED);
                order.setSagaState(OrderStatus.STOCK_REJECTED);
                order.setRejectionReason(reason);
                orderRepository.save(order);
                return StockReservationResult.failed(reason);
            }
            locked.add(inv);
        }

        for (OrderLineRequest item : items) {
            InventoryItem inv = locked.stream().filter(i -> i.getSku().equals(item.sku())).findFirst().orElseThrow();
            int availableNow = inv.getQtyAvailable() - inv.getQtyReserved();
            if (availableNow < item.qty()) {
                String reason = "insufficient stock for " + item.sku() + ": requested " + item.qty() + ", available " + availableNow;
                order.setStatus(OrderStatus.STOCK_REJECTED);
                order.setSagaState(OrderStatus.STOCK_REJECTED);
                order.setRejectionReason(reason);
                orderRepository.save(order);
                return StockReservationResult.failed(reason);
            }
        }

        for (OrderLineRequest item : items) {
            InventoryItem inv = locked.stream().filter(i -> i.getSku().equals(item.sku())).findFirst().orElseThrow();
            inv.setQtyReserved(inv.getQtyReserved() + item.qty());
            inventoryRepository.save(inv);
        }

        order.setSagaState(OrderStatus.STOCK_RESERVED);
        orderRepository.save(order);
        log.info("order {} STOCK_RESERVED", orderId);
        return StockReservationResult.ok();
    }

    @TenantScoped
    @Transactional
    public OrderEntity recordInvoiced(UUID orderId, UUID invoiceId) {
        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        order.setInvoiceId(invoiceId);
        order.setSagaState(OrderStatus.INVOICED);
        orderRepository.save(order);

        order.setStatus(OrderStatus.COMPLETED);
        order.setSagaState(OrderStatus.COMPLETED);
        orderRepository.save(order);

        log.info("order {} COMPLETED - invoiceId={}", orderId, invoiceId);
        return order;
    }

    /**
     * Compensation for reserveStock(): invoice issuance failed after stock
     * was already reserved, so that reservation must be given back - same
     * fixed-alphabetical sku lock ordering as reserveStock() itself, for
     * the same deadlock-avoidance reason.
     */
    @TenantScoped
    @Transactional
    public void releaseStock(UUID orderId, List<OrderLineRequest> items) {
        var sortedSkus = new TreeSet<String>();
        items.forEach(item -> sortedSkus.add(item.sku()));

        List<InventoryItem> locked = new ArrayList<>();
        for (String sku : sortedSkus) {
            inventoryRepository.lockBySku(sku).ifPresent(locked::add);
        }

        for (OrderLineRequest item : items) {
            locked.stream().filter(i -> i.getSku().equals(item.sku())).findFirst().ifPresent(inv -> {
                inv.setQtyReserved(inv.getQtyReserved() - item.qty());
                inventoryRepository.save(inv);
            });
        }
        log.info("order {} stock RELEASED (compensation)", orderId);
    }

    /**
     * Terminal state for a downstream Feign call that threw instead of
     * returning a clean result - see OrderStatus.FAILED's Javadoc. Separate
     * from recordCreditResult/reserveStock's rejection branches, which
     * record a clean business decision the downstream service actually
     * made.
     */
    @TenantScoped
    @Transactional
    public OrderEntity markFailed(UUID orderId, String reason) {
        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.FAILED);
        order.setSagaState(OrderStatus.FAILED);
        order.setRejectionReason(reason);
        orderRepository.save(order);
        log.warn("order {} FAILED: {}", orderId, reason);
        return order;
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public Optional<OrderEntity> findById(UUID id) {
        return orderRepository.findById(id);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public List<OrderEntity> listOrders() {
        // RLS, not this query, enforces that a dealer only sees its own rows.
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }
}
