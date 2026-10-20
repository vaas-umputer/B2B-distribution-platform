package com.imperial.credit.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declaring the SAME exchange here that Invoice Service declares on its own
 * side is normal and safe - RabbitMQ's "declare" is idempotent as long as
 * both sides agree on its properties (durable topic exchange), so it
 * doesn't matter which service's declaration actually creates it first.
 * Each service declares and binds only the queues IT consumes - Invoice
 * never needs to know these exist.
 *
 * <p>credit.events is a SEPARATE exchange from invoice.exchange - it's
 * Credit Service's own domain events (today just
 * credit.limit_increase_requested), not the invoice lifecycle. A direct
 * exchange (not topic) since there's exactly one routing key so far and no
 * need for wildcard matching yet.
 */
@Configuration
public class RabbitMqConfig {

    public static final String INVOICE_EXCHANGE = "invoice.exchange";
    public static final String PAID_QUEUE = "credit-service.invoice-paid";
    public static final String OVERDUE_QUEUE = "credit-service.invoice-overdue";

    public static final String CREDIT_EXCHANGE = "credit.events";
    public static final String LIMIT_INCREASE_REQUESTED_ROUTING_KEY = "credit.limit_increase_requested";
    public static final String LIMIT_INCREASE_REQUESTED_QUEUE = "credit-service.limit-increase-requested-log";

    @Bean
    public TopicExchange invoiceExchange() {
        return new TopicExchange(INVOICE_EXCHANGE, true, false);
    }

    @Bean
    public Queue invoicePaidQueue() {
        return new Queue(PAID_QUEUE, true);
    }

    @Bean
    public Queue invoiceOverdueQueue() {
        return new Queue(OVERDUE_QUEUE, true);
    }

    @Bean
    public Binding paidBinding(Queue invoicePaidQueue, TopicExchange invoiceExchange) {
        return BindingBuilder.bind(invoicePaidQueue).to(invoiceExchange).with("invoice.paid");
    }

    @Bean
    public Binding overdueBinding(Queue invoiceOverdueQueue, TopicExchange invoiceExchange) {
        return BindingBuilder.bind(invoiceOverdueQueue).to(invoiceExchange).with("invoice.overdue");
    }

    @Bean
    public DirectExchange creditEventsExchange() {
        return new DirectExchange(CREDIT_EXCHANGE, true, false);
    }

    /**
     * A deliberate extension point, not a finished feature: today the only
     * consumer is LimitIncreaseRequestedLogger, which just logs. A real
     * notification channel (email, Slack, an admin-dashboard push) would
     * bind its own queue to this same exchange later with no change to the
     * publishing side.
     */
    @Bean
    public Queue limitIncreaseRequestedLogQueue() {
        return new Queue(LIMIT_INCREASE_REQUESTED_QUEUE, true);
    }

    @Bean
    public Binding limitIncreaseRequestedBinding(Queue limitIncreaseRequestedLogQueue, DirectExchange creditEventsExchange) {
        return BindingBuilder.bind(limitIncreaseRequestedLogQueue).to(creditEventsExchange).with(LIMIT_INCREASE_REQUESTED_ROUTING_KEY);
    }
}
