package com.imperial.common.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.imperial.common.web.CorrelationIdFilter;

/**
 * Copies the current request's correlation id (set in MDC by CorrelationIdFilter)
 * onto every outbound Feign call, so a downstream service sees the same id the
 * caller is logging under. Without this, each hop would mint its own id and a
 * single order's journey couldn't be grepped out of the combined logs.
 */
@Component
public class CorrelationIdFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            template.header(CorrelationIdFilter.HEADER_NAME, correlationId);
        }
    }
}
