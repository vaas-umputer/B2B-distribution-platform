package com.imperial.common.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Backs the plan's processed_operations(operation_key PK, processed_at)
 * table: a generic dedup guard for operations that don't already have a
 * natural "does the effect already exist" check (unlike e.g. crediting a
 * lock, where "is there already a credit_locks row for this order" is
 * itself sufficient idempotency - no separate table needed there).
 *
 * <p>Each service that needs this creates its own processed_operations
 * table (same shape) via its own Flyway migration - this class is just the
 * reusable check-then-mark logic against whichever one the local
 * JdbcTemplate points at.
 */
@Component
public class IdempotencyGuard {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean alreadyProcessed(String operationKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_operations WHERE operation_key = ?",
                Integer.class, operationKey);
        return count != null && count > 0;
    }

    /** ON CONFLICT DO NOTHING: a second, concurrent mark for the same key is a no-op, not an error. */
    public void markProcessed(String operationKey) {
        jdbcTemplate.update(
                "INSERT INTO processed_operations (operation_key, processed_at) VALUES (?, now()) " +
                        "ON CONFLICT (operation_key) DO NOTHING",
                operationKey);
    }
}
