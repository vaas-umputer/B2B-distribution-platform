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

    /**
     * Without this, a message that keeps throwing (e.g. invoice.paid for an
     * order whose credit lock/account is missing - IllegalStateException in
     * CreditCheckService.applyReleaseOrConsume) requeues forever under
     * Spring AMQP's default behavior, becoming a poison message that loops
     * indefinitely and starves the queue for every other tenant's events.
     * Combined with the listener retry config in application.yml (3
     * attempts, then reject-without-requeue), an exhausted message lands
     * here instead of looping or being silently discarded, where it's
     * visible and replayable instead of invisible.
     */
    public static final String DEAD_LETTER_EXCHANGE = "invoice.exchange.dlx";
    public static final String PAID_DLQ = PAID_QUEUE + ".dlq";
    public static final String OVERDUE_DLQ = OVERDUE_QUEUE + ".dlq";

    @Bean
    public TopicExchange invoiceExchange() {
        return new TopicExchange(INVOICE_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue invoicePaidQueue() {
        return org.springframework.amqp.core.QueueBuilder.durable(PAID_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", PAID_DLQ)
                .build();
    }

    @Bean
    public Queue invoiceOverdueQueue() {
        return org.springframework.amqp.core.QueueBuilder.durable(OVERDUE_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", OVERDUE_DLQ)
                .build();
    }

    @Bean
    public Queue invoicePaidDlq() {
        return new Queue(PAID_DLQ, true);
    }

    @Bean
    public Queue invoiceOverdueDlq() {
        return new Queue(OVERDUE_DLQ, true);
    }

    @Bean
    public Binding paidDlqBinding(Queue invoicePaidDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(invoicePaidDlq).to(deadLetterExchange).with(PAID_DLQ);
    }

    @Bean
    public Binding overdueDlqBinding(Queue invoiceOverdueDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(invoiceOverdueDlq).to(deadLetterExchange).with(OVERDUE_DLQ);
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
