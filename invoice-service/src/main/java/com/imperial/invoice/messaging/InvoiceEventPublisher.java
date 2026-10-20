package com.imperial.invoice.messaging;

import com.imperial.common.event.InvoiceEvent;
import com.imperial.invoice.domain.Invoice;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InvoiceEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public InvoiceEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishPaid(Invoice invoice) {
        publish("invoice.paid", invoice);
    }

    public void publishOverdue(Invoice invoice) {
        publish("invoice.overdue", invoice);
    }

    private void publish(String routingKey, Invoice invoice) {
        var event = new InvoiceEvent(UUID.randomUUID(), invoice.getId(), invoice.getOrderId(), invoice.getTenantId(), invoice.getAmount());
        rabbitTemplate.convertAndSend(RabbitMqConfig.INVOICE_EXCHANGE, routingKey, event);
    }
}
