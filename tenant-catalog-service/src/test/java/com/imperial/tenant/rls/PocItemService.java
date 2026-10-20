package com.imperial.tenant.rls;

import com.imperial.common.tenant.TenantScoped;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Throwaway Phase-0 proof of concept: every method is @TenantScoped, so
 * TenantRlsAspect stamps the transaction's Postgres session with
 * TenantContext's tenant before the query runs, and the poc_items table's RLS
 * policy (created directly by RlsConcurrencyTest, not a real migration) does
 * the actual filtering. If this isolation holds under concurrent threads,
 * every later service can rely on the same @TenantScoped + aspect pattern
 * instead of hand-adding a WHERE tenant_id = ? to every query.
 */
@Service
class PocItemService {

    private final JdbcTemplate jdbcTemplate;

    PocItemService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @TenantScoped
    @Transactional
    void insert(UUID id, UUID tenantId, String value) {
        jdbcTemplate.update(
                "INSERT INTO poc_items (id, tenant_id, value) VALUES (?, ?, ?)",
                id, tenantId, value);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    List<PocItemRow> findAll() {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, value FROM poc_items",
                (rs, rowNum) -> new PocItemRow(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("tenant_id")),
                        rs.getString("value")));
    }
}
