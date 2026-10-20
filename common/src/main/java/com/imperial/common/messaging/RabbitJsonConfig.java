package com.imperial.common.messaging;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AMQP defaults to Java serialization for message bodies, which is
 * both a security smell (deserializing arbitrary Java objects from a queue)
 * and makes the RabbitMQ management UI show unreadable binary blobs instead
 * of the JSON everyone actually wants to look at during a demo. Declaring
 * this ONE MessageConverter bean is enough - Spring Boot's RabbitAutoConfiguration
 * auto-wires it into both the publishing RabbitTemplate and the
 * @RabbitListener container factory.
 */
@Configuration
public class RabbitJsonConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
