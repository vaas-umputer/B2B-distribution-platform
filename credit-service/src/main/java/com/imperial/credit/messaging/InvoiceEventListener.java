package com.imperial.credit.messaging;

import com.imperial.common.event.InvoiceEvent;
import com.imperial.common.tenant.TenantContext;
import com.imperial.credit.service.CreditCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Credit Service never calls Invoice Service to ask "is this paid yet" - it
 * reacts to what Invoice Service announces. That's what makes this a saga,
 * not RPC: the two services evolve independently, and this is also exactly
 * the seam v2's Invoice Discounting Service plugs into later without
 * Invoice Service's event contract needing to change.
 *
 * <p>There's no HTTP request/JWT here, so nothing populates TenantContext
 * automatically the way JwtAuthenticationFilter does for a web request -
 * this listener sets it itself from the event payload before calling into
 * any @TenantScoped service method, exactly like AuthController does for
 * login.
 */
@Component
public class InvoiceEventListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceEventListener.class);

    private final CreditCheckService creditCheckService;

    public InvoiceEventListener(CreditCheckService creditCheckService) {
        this.creditCheckService = creditCheckService;
    }

    @RabbitListener(queues = RabbitMqConfig.PAID_QUEUE)
    public void onInvoicePaid(InvoiceEvent event) {
        log.info("received invoice.paid for order {} (eventId={})", event.orderId(), event.eventId());
        withTenant(event.tenantId(), () ->
                creditCheckService.consume(event.orderId(), "invoice.paid:" + event.eventId()));
    }

    @RabbitListener(queues = RabbitMqConfig.OVERDUE_QUEUE)
    public void onInvoiceOverdue(InvoiceEvent event) {
        log.info("received invoice.overdue for tenant {} (eventId={})", event.tenantId(), event.eventId());
        withTenant(event.tenantId(), () ->
                creditCheckService.throttleTenant(event.tenantId(), "invoice.overdue:" + event.eventId()));
    }

    private void withTenant(java.util.UUID tenantId, Runnable action) {
        TenantContext.set(tenantId);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }
}
