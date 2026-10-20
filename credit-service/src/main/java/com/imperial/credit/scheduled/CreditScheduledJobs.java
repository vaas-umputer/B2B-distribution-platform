package com.imperial.credit.scheduled;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Both jobs here are genuinely cross-tenant (a near-limit scan and a pool
 * utilization report have to look across every tenant at once), which is
 * exactly the narrow case systemJdbcTemplate (svc_system, BYPASSRLS)
 * exists for - see SystemJdbcConfig's Javadoc. Nothing else in this
 * service uses that connection.
 */
@Component
public class CreditScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(CreditScheduledJobs.class);
    private static final BigDecimal NEAR_LIMIT_THRESHOLD = new BigDecimal("0.90");

    private final JdbcTemplate systemJdbcTemplate;

    public CreditScheduledJobs(@Qualifier("systemJdbcTemplate") JdbcTemplate systemJdbcTemplate) {
        this.systemJdbcTemplate = systemJdbcTemplate;
    }

    /** Every 15 minutes per the plan; demo-friendly override via property if you don't want to wait. */
    @Scheduled(cron = "${credit.near-limit-alert.cron:0 */15 * * * *}")
    public void flagNearLimitTenants() {
        List<UtilizationRow> rows = systemJdbcTemplate.query(
                "SELECT tenant_id, outstanding, credit_limit FROM credit_accounts WHERE credit_limit > 0",
                (rs, i) -> new UtilizationRow(
                        UUID.fromString(rs.getString("tenant_id")),
                        rs.getBigDecimal("outstanding"),
                        rs.getBigDecimal("credit_limit")));

        int flagged = 0;
        for (UtilizationRow row : rows) {
            BigDecimal utilization = row.outstanding.divide(row.creditLimit, 4, RoundingMode.HALF_UP);
            if (utilization.compareTo(NEAR_LIMIT_THRESHOLD) >= 0) {
                systemJdbcTemplate.update(
                        "INSERT INTO alerts (id, tenant_id, utilization, created_at) VALUES (?, ?, ?, now())",
                        UUID.randomUUID(), row.tenantId, utilization);
                flagged++;
            }
        }
        if (flagged > 0) {
            log.info("near-limit alert sweep: flagged {} tenant(s) at >= {} utilization", flagged, NEAR_LIMIT_THRESHOLD);
        }
    }

    /** First of the month per the plan; demo-friendly override via property. */
    @Scheduled(cron = "${credit.monthly-review.cron:0 0 0 1 * *}")
    public void compileMonthlyReport() {
        Integer nearLimitCount = systemJdbcTemplate.queryForObject(
                "SELECT count(*) FROM credit_accounts WHERE credit_limit > 0 AND outstanding / credit_limit >= ?",
                Integer.class, NEAR_LIMIT_THRESHOLD);

        BigDecimal totalOutstanding = systemJdbcTemplate.queryForObject(
                "SELECT total_outstanding FROM pool_balance WHERE id = 1", BigDecimal.class);
        BigDecimal cash = systemJdbcTemplate.queryForObject("SELECT cash FROM pool_balance WHERE id = 1", BigDecimal.class);
        BigDecimal committed = systemJdbcTemplate.queryForObject("SELECT committed_payables FROM pool_balance WHERE id = 1", BigDecimal.class);
        BigDecimal buffer = systemJdbcTemplate.queryForObject("SELECT operating_buffer FROM pool_balance WHERE id = 1", BigDecimal.class);
        BigDecimal bufferPercent = systemJdbcTemplate.queryForObject("SELECT buffer_percent FROM pool_balance WHERE id = 1", BigDecimal.class);

        BigDecimal allocatablePool = cash.subtract(committed).subtract(buffer).multiply(BigDecimal.ONE.subtract(bufferPercent));
        BigDecimal poolUtilization = allocatablePool.signum() == 0
                ? BigDecimal.ZERO
                : totalOutstanding.divide(allocatablePool, 4, RoundingMode.HALF_UP);

        systemJdbcTemplate.update(
                "INSERT INTO monthly_reports (id, report_month, near_limit_tenant_count, pool_utilization_percent, created_at) " +
                        "VALUES (?, ?, ?, ?, now())",
                UUID.randomUUID(), LocalDate.now().withDayOfMonth(1), nearLimitCount, poolUtilization);

        log.info("monthly review compiled: {} near-limit tenant(s), pool utilization {}", nearLimitCount, poolUtilization);
    }

    private record UtilizationRow(UUID tenantId, BigDecimal outstanding, BigDecimal creditLimit) {
    }
}
