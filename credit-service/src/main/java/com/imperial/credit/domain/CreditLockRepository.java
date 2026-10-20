package com.imperial.credit.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CreditLockRepository extends JpaRepository<CreditLock, UUID> {

    /**
     * One lock per order, so "does a lock already exist for this order"
     * is itself a sufficient idempotency check for lockCredit - no
     * separate processed_operations entry needed for that path.
     */
    Optional<CreditLock> findByOrderId(UUID orderId);
}
