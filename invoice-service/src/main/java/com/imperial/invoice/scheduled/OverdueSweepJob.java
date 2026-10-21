package com.imperial.invoice.scheduled;

import com.imperial.common.tenant.TenantContext;
import com.imperial.invoice.service.InvoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Two-phase, by design: the cross-tenant DISCOVERY query runs on the
 * privileged systemJdbcTemplate (just a read, just two columns), but the
 * actual WRITE - transitioning one invoice to OVERDUE and publishing the
 * event - goes through InvoiceService's normal @TenantScoped path, with
 * TenantContext set to that one invoice's own tenant first. The elevated
 * privilege is used for the smallest possible slice of this job; every
 * mutation still happens under the same RLS-enforced connection and
 * TenantScoped discipline as everything else in this service.
 */
@Component
public class OverdueSweepJob {

    private static final Logger log = LoggerFactory.getLogger(OverdueSweepJob.class);

    private final JdbcTemplate systemJdbcTemplate;
    private final InvoiceService invoiceService;

    public OverdueSweepJob(JdbcTemplate systemJdbcTemplate, InvoiceService invoiceService) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.invoiceService = invoiceService;
    }

    /** Daily per the plan; demo-friendly override via property if you don't want to wait for a real due date to pass. */
    @Scheduled(cron = "${invoice.overdue-sweep.cron:0 0 2 * * *}")
    public void sweepOverdueInvoices() {
        List<Candidate> candidates = systemJdbcTemplate.query(
                "SELECT id, tenant_id FROM invoices WHERE status = 'ISSUED' AND due_date < CURRENT_DATE",
                (rs, i) -> new Candidate(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("tenant_id"))));

        if (candidates.isEmpty()) {
            return;
        }
        log.info("overdue sweep: {} invoice(s) past due", candidates.size());
        int failures = 0;
        for (Candidate candidate : candidates) {
            TenantContext.set(candidate.tenantId());
            try {
                invoiceService.markOverdue(candidate.invoiceId());
            } catch (RuntimeException e) {
                // One bad row (an illegal-transition 409, a RabbitMQ
                // publish failure inside markOverdue's same transaction,
                // etc.) must not abort the whole run - every other
                // tenant's overdue invoices still need to be swept today.
                failures++;
                log.error("overdue sweep: failed to mark invoice {} (tenant {}) overdue - will retry on the next run",
                        candidate.invoiceId(), candidate.tenantId(), e);
            } finally {
                TenantContext.clear();
            }
        }
        if (failures > 0) {
            log.warn("overdue sweep: {} of {} invoice(s) failed this run", failures, candidates.size());
        }
    }

    private record Candidate(UUID invoiceId, UUID tenantId) {
    }
}
