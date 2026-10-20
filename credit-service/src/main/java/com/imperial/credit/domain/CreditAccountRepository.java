package com.imperial.credit.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface CreditAccountRepository extends JpaRepository<CreditAccount, UUID> {

    /**
     * SELECT ... FOR UPDATE on this one tenant's row. Callers must acquire
     * the pool_balance lock FIRST, always - see CreditCheckService's
     * Javadoc for why consistent lock ordering is what rules out deadlocks
     * between two concurrent orders.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CreditAccount c where c.tenantId = :tenantId")
    Optional<CreditAccount> lockByTenantId(@Param("tenantId") UUID tenantId);
}
