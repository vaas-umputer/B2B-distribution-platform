package com.imperial.credit.service;

import com.imperial.credit.dto.AlertView;
import com.imperial.credit.dto.MonthlyReportView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

/**
 * Plain app_user JdbcTemplate is fine here - alerts and monthly_reports
 * were deliberately NOT given RLS policies in Phase 2 (they're inherently
 * cross-tenant, admin-facing tables), so there's no isolation to bypass
 * and no need for the privileged svc_system connection the scheduled jobs
 * use to WRITE them.
 */
@Service
public class AdminReadService {

    private final JdbcTemplate jdbcTemplate;

    public AdminReadService(@Qualifier("systemJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AlertView> listAlerts() {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, utilization, created_at FROM alerts ORDER BY created_at DESC",
                (rs, i) -> new AlertView(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("tenant_id")),
                        rs.getBigDecimal("utilization"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    public Optional<MonthlyReportView> latestMonthlyReport() {
        List<MonthlyReportView> rows = jdbcTemplate.query(
                "SELECT report_month, near_limit_tenant_count, pool_utilization_percent " +
                        "FROM monthly_reports ORDER BY report_month DESC LIMIT 1",
                (rs, i) -> new MonthlyReportView(
                        rs.getDate("report_month").toLocalDate(),
                        rs.getInt("near_limit_tenant_count"),
                        rs.getBigDecimal("pool_utilization_percent")));
        return rows.stream().findFirst();
    }

    public List<UtilizationView> listUtilization() {
        return jdbcTemplate.query(
                "SELECT tenant_id, credit_limit, outstanding, throttled FROM credit_accounts ORDER BY tenant_id",
                (rs, i) -> new UtilizationView(UUID.fromString(rs.getString("tenant_id")),
                        rs.getBigDecimal("credit_limit"), rs.getBigDecimal("outstanding"), rs.getBoolean("throttled")));
    }

    public record UtilizationView(UUID tenantId, BigDecimal creditLimit, BigDecimal outstanding, boolean throttled) { }
}
