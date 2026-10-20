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

        LockCreditResponse creditResponse = creditServiceClient.lockCredit(
                new LockCreditRequest(tenantId, order.getId(), totalValue));
        order = orderStepService.recordCreditResult(order.getId(), creditResponse.approved(), creditResponse.reason());

        if (!creditResponse.approved()) {
            return toView(order);
        }

        StockReservationResult stockResult = orderStepService.reserveStock(order.getId(), items, simulateStockFailure);
        if (!stockResult.success()) {
            log.info("order {} stock check failed - releasing credit lock {} (compensation)", order.getId(), creditResponse.lockId());
            creditServiceClient.releaseCredit(creditResponse.lockId());
            order = orderStepService.findById(order.getId()).orElseThrow();
            return toView(order);
        }

        IssueInvoiceResponse invoiceResponse = invoiceServiceClient.issueInvoice(
                new IssueInvoiceRequest(order.getId(), tenantId, totalValue, "NET_30"));
        order = orderStepService.recordInvoiced(order.getId(), invoiceResponse.invoiceId());

        return toView(order);
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
