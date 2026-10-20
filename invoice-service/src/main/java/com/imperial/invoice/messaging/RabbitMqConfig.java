package com.imperial.invoice.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Invoice Service only ever publishes - it declares the exchange and
 * nothing else. Each consumer (Credit Service today; a notifications
 * service or v2's Invoice Discounting Service later) declares and binds
 * its own queue to this same exchange on its own side, without Invoice
 * Service needing to know who's listening.
 */
@Configuration
public class RabbitMqConfig {

    public static final String INVOICE_EXCHANGE = "invoice.exchange";

    @Bean
    public TopicExchange invoiceExchange() {
        return new TopicExchange(INVOICE_EXCHANGE, true, false);
    }
}
