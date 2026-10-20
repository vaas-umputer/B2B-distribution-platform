package com.imperial.common.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Forwards the caller's own Authorization header onto outbound Feign calls.
 * Order & Inventory Service's saga calls Credit and Invoice Service on
 * behalf of the tenant who placed the order; without this, Credit/Invoice
 * would see no JWT at all and reject every orchestrated call, even though a
 * legitimately authenticated request started the whole chain.
 *
 * <p>This is the standard "propagate the caller's identity downstream"
 * pattern - the same job an API gateway's auth passthrough does, done here
 * at the Feign layer since Phase 1 has no gateway yet.
 */
@Component
public class AuthHeaderFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String authHeader = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authHeader != null) {
                template.header(HttpHeaders.AUTHORIZATION, authHeader);
            }
        }
    }
}
