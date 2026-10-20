package com.imperial.credit.domain;

import org.springframework.stereotype.Component;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * credit_overrides is append-only and every row is a brand-new insert, so
 * there's no upsert ambiguity here the way there is for CreditAccount/
 * CreditRiskScore - a plain JdbcTemplate insert is simpler than standing up
 * a full JPA entity + Persistable just for "write one row, never read it
 * back from this service."
 */
@Component
public class CreditOverrideWriter {

    @PersistenceContext
    private EntityManager entityManager;

    public void record(UUID tenantId, BigDecimal oldLimit, BigDecimal newLimit, String reason, String setBy) {
        // Called only by CreditCheckService's tenant-scoped transaction. Keeping
        // it in that transaction makes the account mutation and audit insert
        // atomic, without nesting a second RLS aspect around the JDBC write.
        entityManager.createNativeQuery(
                "INSERT INTO credit_overrides (id, tenant_id, old_limit, new_limit, override_reason, set_by, set_at) " +
                        "VALUES (:id, :tenantId, :oldLimit, :newLimit, :reason, :setBy, :setAt)")
                .setParameter("id", UUID.randomUUID())
                .setParameter("tenantId", tenantId)
                .setParameter("oldLimit", oldLimit)
                .setParameter("newLimit", newLimit)
                .setParameter("reason", reason)
                .setParameter("setBy", setBy)
                .setParameter("setAt", Timestamp.from(Instant.now()))
                .executeUpdate();
    }
}
