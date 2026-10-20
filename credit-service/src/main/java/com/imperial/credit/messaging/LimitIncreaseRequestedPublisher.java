package com.imperial.credit.messaging;

import com.imperial.credit.domain.LimitIncreaseRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class LimitIncreaseRequestedPublisher {

    private static final Logger log = LoggerFactory.getLogger(LimitIncreaseRequestedPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public LimitIncreaseRequestedPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Best-effort: this is a notification side-channel (the plan's own
     * words - "a deliberate extension point... costing almost nothing to
     * add now"), not part of the core guarantee this request's submission
     * is making. If RabbitMQ is unreachable, the tenant's request still
     * gets written and still lands in the admin queue; they just don't get
     * the (currently log-only) notification. Letting a broker outage block
     * the actual business write would make a side-channel's uptime a
     * dependency of the core feature, which is exactly backwards.
     */
    public void publish(LimitIncreaseRequest request) {
        try {
            var event = new LimitIncreaseRequestedEvent(
                    UUID.randomUUID(), request.getId(), request.getTenantId(), request.getRequestedAmount(), request.getReason());
            rabbitTemplate.convertAndSend(RabbitMqConfig.CREDIT_EXCHANGE, RabbitMqConfig.LIMIT_INCREASE_REQUESTED_ROUTING_KEY, event);
        } catch (AmqpException e) {
            log.warn("failed to publish credit.limit_increase_requested for request {} - continuing anyway", request.getId(), e);
        }
    }
}
