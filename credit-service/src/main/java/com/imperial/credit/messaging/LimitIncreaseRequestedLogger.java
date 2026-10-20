package com.imperial.credit.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The plan's own words: "a deliberate extension point for a real
 * notification channel later, costing almost nothing to add now." This is
 * that extension point filled in with the simplest possible consumer -
 * nothing here depends on this event existing, so a real notifications
 * service could bind its own queue to the same exchange tomorrow without
 * touching anything on the publishing side.
 */
@Component
public class LimitIncreaseRequestedLogger {

    private static final Logger log = LoggerFactory.getLogger(LimitIncreaseRequestedLogger.class);

    @RabbitListener(queues = RabbitMqConfig.LIMIT_INCREASE_REQUESTED_QUEUE)
    public void onLimitIncreaseRequested(LimitIncreaseRequestedEvent event) {
        log.info("[notification stand-in] tenant {} requested a limit increase to {} (requestId={}, reason={})",
                event.tenantId(), event.requestedAmount(), event.requestId(), event.reason());
    }
}
