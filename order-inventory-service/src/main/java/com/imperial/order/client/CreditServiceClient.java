package com.imperial.order.client;

import com.imperial.common.dto.LockCreditRequest;
import com.imperial.common.dto.LockCreditResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

/**
 * The one real network hop in the saga's happy path, and the one
 * compensating call on the failure path. "name" is only a label Feign uses
 * for logging/metrics here (no service discovery - see the plan's trade-off
 * #4); the actual address comes from "url", resolved from this service's own
 * application.yml (a container hostname in Docker Compose, localhost
 * otherwise).
 */
@FeignClient(name = "credit-service", url = "${credit-service.url}")
public interface CreditServiceClient {

    @PostMapping("/credit/lock")
    LockCreditResponse lockCredit(@RequestBody LockCreditRequest request);

    @PostMapping("/credit/release/{lockId}")
    void releaseCredit(@PathVariable UUID lockId);
}
