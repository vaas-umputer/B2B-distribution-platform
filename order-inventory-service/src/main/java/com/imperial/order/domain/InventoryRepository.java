package com.imperial.order.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<InventoryItem, String> {

    /**
     * SELECT ... FOR UPDATE on one sku. Callers must lock every sku in an
     * order's line items in a FIXED, deterministic order (alphabetical by
     * sku - see OrderStepService) so two concurrent orders that both touch
     * SKU-001 and SKU-002 always acquire them in the same sequence, ruling
     * out a lock-ordering deadlock the same way Credit Service's
     * pool-then-account ordering does.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.sku = :sku")
    Optional<InventoryItem> lockBySku(@Param("sku") String sku);
}
