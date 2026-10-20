package com.imperial.credit.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface PoolBalanceRepository extends JpaRepository<PoolBalance, Short> {

    /**
     * SELECT ... FOR UPDATE on the one singleton row. Must be acquired
     * BEFORE any credit_accounts row in the same transaction - see
     * CreditCheckService's Javadoc.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PoolBalance p where p.id = 1")
    PoolBalance lockTheSingleton();
}
