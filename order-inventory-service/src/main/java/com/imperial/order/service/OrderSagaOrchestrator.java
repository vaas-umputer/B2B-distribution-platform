package com.imperial.order.service;

import com.imperial.common.dto.IssueInvoiceRequest;
import com.imperial.common.dto.IssueInvoiceResponse;
import com.imperial.common.dto.LockCreditRequest;
import com.imperial.common.dto.LockCreditResponse;
import com.imperial.order.client.CreditServiceClient;
import com.imperial.order.client.InvoiceServiceClient;
import com.imperial.order.domain.OrderEntity;
import com.imperial.order.domain.OrderStatus;
import com.imperial.order.dto.OrderLineRequest;
import com.imperial.order.dto.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Order & Inventory Service is the saga orchestrator (plan decision #3).
 * This class is deliberately NOT @Transactional itself - every step below
 * is a separate call into OrderStepService, each one its own real,
 * independently-committing transaction (see that class's Javadoc). That
 * means:
 * <ul>
 *   <li>no database transaction is ever held open across a network call to
 *       Credit or Invoice Service - a slow or stuck downstream call can't
 *       pin a DB connection for its duration;</li>
 *   <li>a crash between any two steps leaves the order's saga_state exactly
 *       where it stopped, durably, not rolled back to nothing.</li>
 * </ul>
 * Compensation stays exactly what Phase 1 established: if stock reservation
 * fails after credit was already locked, release that lock. Idempotency is
 * request-level - a retried call with the same Idempotency-Key is answered
 * from the existing order without re-touching Credit or Invoice at all.
 */
@Service
public class OrderSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaOrchestrator.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final OrderStepService orderStepService;
    private final CreditServiceClient creditServiceClient;
    private final InvoiceServiceClient invoiceServiceClient;

    public OrderSagaOrchestrator(OrderStepService orderStepService,
                                  CreditServiceClient creditServiceClient,
                                  InvoiceServiceClient invoiceServiceClient) {
        this.orderStepService = orderStepService;
        this.creditServiceClient = creditServiceClient;
        this.invoiceServiceClient = invoiceServiceClient;
    }

    public OrderView placeOrder(UUID tenantId, List<OrderLineRequest> items, boolean simulateStockFailure, String idempotencyKey) {
        Optional<OrderEntity> existing = orderStepService.findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            log.info("idempotent replay for key {} - returning existing order {} unchanged", idempotencyKey, existing.get().getId());
            return toView(existing.get());
        }

        BigDecimal totalValue = computeTotal(items);
        OrderEntity order = orderStepService.createOrder(tenantId, items, totalValue, idempotencyKey);

        LockCreditResponse creditResponse;
        try {
            creditResponse = creditServiceClient.lockCredit(
                    new LockCreditRequest(tenantId, order.getId(), totalValue));
        } catch (RuntimeException e) {
            // Nothing committed on our side beyond PLACED, so there's
            // nothing to compensate here - but we genuinely don't know
            // whether Credit Service itself applied the lock before this
            // call failed (timeout/connection-refused/5xx all look the
            // same from here). Credit Service's own lockCredit is
            // idempotent by orderId, so if it DID apply, a future retry of
            // this exact order would be a safe no-op rather than a double
            // lock - but nothing automatically retries today, hence FAILED
            // rather than a clean rejection.
            log.error("order {} credit lock call failed - downstream outcome unknown", order.getId(), e);
            order = orderStepService.markFailed(order.getId(), "credit check failed: " + e.getMessage());
            return toView(order);
        }
        order = orderStepService.recordCreditResult(order.getId(), creditResponse.approved(), creditResponse.reason());

        if (!creditResponse.approved()) {
            return toView(order);
        }

        StockReservationResult stockResult = orderStepService.reserveStock(order.getId(), items, simulateStockFailure);
        if (!stockResult.success()) {
            log.info("order {} stock check failed - releasing credit lock {} (compensation)", order.getId(), creditResponse.lockId());
            releaseCreditBestEffort(creditResponse.lockId());
            order = orderStepService.findById(order.getId()).orElseThrow();
            return toView(order);
        }

        IssueInvoiceResponse invoiceResponse;
        try {
            invoiceResponse = invoiceServiceClient.issueInvoice(
                    new IssueInvoiceRequest(order.getId(), tenantId, totalValue, "NET_30"));
        } catch (RuntimeException e) {
            // Unlike the credit-lock failure above, stock IS already
            // reserved and credit IS already locked at this point - both
            // must be compensated, or the tenant permanently loses that
            // stock/credit capacity for an order that never completed.
            log.error("order {} invoice issuance failed - releasing credit lock {} and stock (compensation)",
                    order.getId(), creditResponse.lockId(), e);
            releaseCreditBestEffort(creditResponse.lockId());
            orderStepService.releaseStock(order.getId(), items);
            order = orderStepService.markFailed(order.getId(), "invoice issuance failed: " + e.getMessage());
            return toView(order);
        }
        order = orderStepService.recordInvoiced(order.getId(), invoiceResponse.invoiceId());

        return toView(order);
    }

    /** A failed release here must never mask the ORIGINAL failure that triggered compensation - log and move on, don't throw. */
    private void releaseCreditBestEffort(UUID lockId) {
        try {
            creditServiceClient.releaseCredit(lockId);
        } catch (RuntimeException e) {
            log.error("compensation FAILED: could not release credit lock {} - requires manual reconciliation", lockId, e);
        }
    }

    public Optional<OrderView> findById(UUID id) {
        return orderStepService.findById(id).map(this::toView);
    }

    public List<OrderView> listOrders() {
        return orderStepService.listOrders().stream().map(this::toView).toList();
    }

    private OrderView toView(OrderEntity order) {
        return new OrderView(order.getId(), order.getStatus().name(), order.getTotalValue(), order.getInvoiceId(), order.getRejectionReason());
    }

    private BigDecimal computeTotal(List<OrderLineRequest> items) {
        return items.stream()
                .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.qty())))
                .reduce(ZERO, BigDecimal::add);
    }
}
