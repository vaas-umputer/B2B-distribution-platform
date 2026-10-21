package com.imperial.order.web;

import com.imperial.common.tenant.TenantContext;
import com.imperial.order.domain.OrderStatus;
import com.imperial.order.dto.OrderView;
import com.imperial.order.dto.PlaceOrderRequest;
import com.imperial.order.service.OrderSagaOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Set;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final Set<OrderStatus> REJECTED_STATUSES = Set.of(
            OrderStatus.CREDIT_REJECTED, OrderStatus.STOCK_REJECTED);
    private static final Set<OrderStatus> FAILED_STATUSES = Set.of(OrderStatus.FAILED);

    private final OrderSagaOrchestrator orderSagaOrchestrator;

    public OrderController(OrderSagaOrchestrator orderSagaOrchestrator) {
        this.orderSagaOrchestrator = orderSagaOrchestrator;
    }

    @PostMapping
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<OrderView> placeOrder(
            @Valid @RequestBody PlaceOrderRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(name = "simulateStockFailure", defaultValue = "false") boolean simulateStockFailure) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // Required, not optional: a financial write with no replay-safety
            // key is exactly the "retries are normal, duplicate side effects
            // are the dangerous bug" case the plan calls out explicitly.
            throw new ResponseStatusException(BAD_REQUEST, "Idempotency-Key header is required");
        }

        UUID tenantId = TenantContext.get();
        OrderView result = orderSagaOrchestrator.placeOrder(tenantId, request.items(), simulateStockFailure, idempotencyKey);

        OrderStatus resultStatus = OrderStatus.valueOf(result.status());
        HttpStatus status = REJECTED_STATUSES.contains(resultStatus) ? HttpStatus.UNPROCESSABLE_ENTITY
                : FAILED_STATUSES.contains(resultStatus) ? HttpStatus.BAD_GATEWAY
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/{id}")
    public OrderView getOrder(@PathVariable UUID id) {
        return orderSagaOrchestrator.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
    }

    @GetMapping
    public List<OrderView> listOrders() {
        return orderSagaOrchestrator.listOrders();
    }
}
