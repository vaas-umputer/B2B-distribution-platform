package com.imperial.credit.concurrency;

import com.imperial.common.tenant.TenantContext;
import com.imperial.credit.CreditServiceApplication;
import com.imperial.credit.domain.LimitIncreaseStatus;
import com.imperial.credit.service.CreditCheckService;
import com.imperial.credit.service.LimitIncreaseRequestService;
import com.imperial.credit.service.OverrideBelowOutstandingException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3's own validation list, almost verbatim:
 * - submit -> admin approves -> tenant's credit_limit updates -> a
 *   subsequent order (here: a direct lockCredit call) can actually use the
 *   new headroom - not just that the number changed.
 * - an override below current outstanding is REJECTED with a clear error,
 *   never silently adjusted.
 * - the audit trail (credit_overrides) reads as a coherent history across
 *   both a manual override and an approved request.
 */
@Testcontainers
@SpringBootTest(classes = CreditServiceApplication.class, properties = "spring.flyway.enabled=false")
class OperationalWorkflowTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("credit_db");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_user");
        registry.add("spring.datasource.password", () -> "app_user");
        // Mirrors production exactly: a genuinely BYPASSRLS role, separate
        // from app_user - see infra/postgres-init and SystemJdbcConfig's
        // Javadoc. Without this, the admin-listing query under test would
        // see zero rows regardless of real data, for the same reason every
        // other test in this module pins a connection before touching an
        // RLS table outside @TenantScoped (TenantRlsAspect's Javadoc).
        registry.add("system.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("system.datasource.username", () -> "svc_system");
        registry.add("system.datasource.password", () -> "svc_system");
    }

    @BeforeAll
    static void createSchemaAndRoles() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = admin.createStatement()) {

            stmt.execute("CREATE ROLE app_user LOGIN PASSWORD 'app_user'");
            stmt.execute("CREATE ROLE svc_system LOGIN PASSWORD 'svc_system' BYPASSRLS");
            stmt.execute("GRANT ALL ON SCHEMA public TO app_user");
            stmt.execute("GRANT ALL ON SCHEMA public TO svc_system");

            stmt.execute("""
                    CREATE TABLE credit_accounts (
                        tenant_id UUID PRIMARY KEY, credit_limit NUMERIC NOT NULL,
                        outstanding NUMERIC NOT NULL DEFAULT 0, throttled BOOLEAN NOT NULL DEFAULT false,
                        version INT NOT NULL DEFAULT 0
                    )""");
            stmt.execute("ALTER TABLE credit_accounts ENABLE ROW LEVEL SECURITY");
            stmt.execute("ALTER TABLE credit_accounts FORCE ROW LEVEL SECURITY");
            stmt.execute("""
                    CREATE POLICY p ON credit_accounts
                        USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
                        WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid)
                    """);

            stmt.execute("""
                    CREATE TABLE pool_balance (
                        id SMALLINT PRIMARY KEY DEFAULT 1, cash NUMERIC NOT NULL,
                        committed_payables NUMERIC NOT NULL DEFAULT 0, operating_buffer NUMERIC NOT NULL DEFAULT 0,
                        buffer_percent NUMERIC NOT NULL DEFAULT 0, total_outstanding NUMERIC NOT NULL DEFAULT 0,
                        version INT NOT NULL DEFAULT 0
                    )""");

            stmt.execute("""
                    CREATE TABLE credit_locks (
                        id UUID PRIMARY KEY, tenant_id UUID NOT NULL, order_id UUID NOT NULL,
                        amount NUMERIC NOT NULL, status TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("ALTER TABLE credit_locks ENABLE ROW LEVEL SECURITY");
            stmt.execute("ALTER TABLE credit_locks FORCE ROW LEVEL SECURITY");
            stmt.execute("""
                    CREATE POLICY p ON credit_locks
                        USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
                        WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid)
                    """);

            stmt.execute("""
                    CREATE TABLE credit_risk_scores (
                        tenant_id UUID PRIMARY KEY, declared_volume NUMERIC NOT NULL, business_vintage INT NOT NULL,
                        collateral NUMERIC NOT NULL, relationship_category INT NOT NULL, computed_score INT NOT NULL,
                        suggested_limit NUMERIC NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("ALTER TABLE credit_risk_scores ENABLE ROW LEVEL SECURITY");
            stmt.execute("ALTER TABLE credit_risk_scores FORCE ROW LEVEL SECURITY");
            stmt.execute("""
                    CREATE POLICY p ON credit_risk_scores
                        USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
                        WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid)
                    """);

            stmt.execute("CREATE TABLE processed_operations (operation_key TEXT PRIMARY KEY, processed_at TIMESTAMPTZ NOT NULL DEFAULT now())");

            stmt.execute("""
                    CREATE TABLE credit_overrides (
                        id UUID PRIMARY KEY, tenant_id UUID NOT NULL, old_limit NUMERIC, new_limit NUMERIC NOT NULL,
                        override_reason TEXT NOT NULL, set_by TEXT NOT NULL, set_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("ALTER TABLE credit_overrides ENABLE ROW LEVEL SECURITY");
            stmt.execute("ALTER TABLE credit_overrides FORCE ROW LEVEL SECURITY");
            stmt.execute("""
                    CREATE POLICY p ON credit_overrides
                        USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
                        WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid)
                    """);

            stmt.execute("""
                    CREATE TABLE limit_increase_requests (
                        id UUID PRIMARY KEY, tenant_id UUID NOT NULL, requested_amount NUMERIC NOT NULL, reason TEXT,
                        status TEXT NOT NULL, reviewed_by TEXT, reviewed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("ALTER TABLE limit_increase_requests ENABLE ROW LEVEL SECURITY");
            stmt.execute("ALTER TABLE limit_increase_requests FORCE ROW LEVEL SECURITY");
            stmt.execute("""
                    CREATE POLICY p ON limit_increase_requests
                        USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
                        WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid)
                    """);

            // No RLS - admin-facing, inherently cross-tenant (see Phase 2/3).
            stmt.execute("""
                    CREATE TABLE alerts (
                        id UUID PRIMARY KEY, tenant_id UUID NOT NULL, utilization NUMERIC NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("""
                    CREATE TABLE monthly_reports (
                        id UUID PRIMARY KEY, report_month DATE NOT NULL, near_limit_tenant_count INT NOT NULL,
                        pool_utilization_percent NUMERIC NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");

            stmt.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO app_user");
            stmt.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO svc_system");
            stmt.execute("INSERT INTO pool_balance (id, cash) VALUES (1, 100000000)"); // generous - pool is never the binding constraint in these tests
        }
    }

    @Autowired
    private CreditCheckService creditCheckService;

    @Autowired
    private LimitIncreaseRequestService limitIncreaseRequestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        creditCheckService.provisionAccount(tenantId, new BigDecimal("100000"), "test-setup");
        TenantContext.clear();
    }

    @org.springframework.beans.factory.annotation.Qualifier("systemJdbcTemplate")
    @Autowired
    private JdbcTemplate diagnosticSystemJdbcTemplate;

    @Test
    void diagnosticCheckSystemJdbcTemplateRole() {
        String user = diagnosticSystemJdbcTemplate.queryForObject("SELECT current_user", String.class);
        assertThat(user).isEqualTo("svc_system");
    }

    @Test
    void approvedLimitIncreaseRequest_updatesLimitAndUnlocksTheNewHeadroom() {
        TenantContext.set(tenantId);
        var request = limitIncreaseRequestService.submit(tenantId, new BigDecimal("300000"), "need more headroom for a big order");
        TenantContext.clear();

        var approved = limitIncreaseRequestService.approve(request.getId(), "admin-test");
        assertThat(approved.getStatus()).isEqualTo(LimitIncreaseStatus.APPROVED);

        BigDecimal newLimit = readLimit(tenantId);
        assertThat(newLimit).isEqualByComparingTo(new BigDecimal("300000"));

        // The actual point: an order that would have exceeded the OLD
        // 100,000 limit must now succeed against the NEW one.
        TenantContext.set(tenantId);
        var lockResponse = creditCheckService.lockCredit(tenantId, UUID.randomUUID(), new BigDecimal("250000"));
        TenantContext.clear();
        assertThat(lockResponse.approved()).isTrue();
    }

    @Test
    void manualOverrideBelowOutstanding_isRejectedNotClamped() {
        TenantContext.set(tenantId);
        creditCheckService.lockCredit(tenantId, UUID.randomUUID(), new BigDecimal("80000")); // outstanding now 80,000
        TenantContext.clear();

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() ->
                    creditCheckService.overrideLimit(tenantId, new BigDecimal("50000"), "typo in admin console", "admin-test"))
                    .isInstanceOf(OverrideBelowOutstandingException.class);
        } finally {
            TenantContext.clear();
        }

        // Unchanged - not silently clamped to 80,000, still the original 100,000.
        assertThat(readLimit(tenantId)).isEqualByComparingTo(new BigDecimal("100000"));
    }

    @Test
    void auditTrailCoversBothManualOverrideAndApprovedRequest() {
        TenantContext.set(tenantId);
        creditCheckService.overrideLimit(tenantId, new BigDecimal("150000"), "manual bump for a trusted tenant", "admin-test");
        TenantContext.clear();

        TenantContext.set(tenantId);
        var request = limitIncreaseRequestService.submit(tenantId, new BigDecimal("200000"), "steady growth");
        TenantContext.clear();
        limitIncreaseRequestService.approve(request.getId(), "admin-test");

        // 3, not 2: @BeforeEach's provisionAccount call also writes its own
        // "admin provisioning" override row - this test only asserts that
        // BOTH new paths (manual override, approved request) added their
        // own row to the SAME trail, not an exact count.
        List<String> reasons = readOverrideReasons(tenantId);
        assertThat(reasons).hasSize(3);
        assertThat(reasons).anySatisfy(r -> assertThat(r).contains("manual bump"));
        assertThat(reasons).anySatisfy(r -> assertThat(r).contains("limit increase request approved"));
    }

    @Test
    void rejectedRequest_leavesLimitUntouchedAndWritesNoOverride() {
        TenantContext.set(tenantId);
        var request = limitIncreaseRequestService.submit(tenantId, new BigDecimal("999999"), "just in case");
        TenantContext.clear();

        var rejected = limitIncreaseRequestService.reject(request.getId(), "admin-test");
        assertThat(rejected.getStatus()).isEqualTo(LimitIncreaseStatus.REJECTED);
        assertThat(readLimit(tenantId)).isEqualByComparingTo(new BigDecimal("100000"));
        // Only @BeforeEach's own "admin provisioning" row - rejection itself writes no override.
        assertThat(readOverrideReasons(tenantId)).hasSize(1);
    }

    private BigDecimal readLimit(UUID tenantId) {
        return jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<BigDecimal>) con -> {
            try (var stmt = con.createStatement()) {
                stmt.execute("SELECT set_config('app.current_tenant', '" + tenantId + "', false)");
                var rs = stmt.executeQuery("SELECT credit_limit FROM credit_accounts WHERE tenant_id = '" + tenantId + "'");
                rs.next();
                return rs.getBigDecimal("credit_limit");
            }
        });
    }

    private List<String> readOverrideReasons(UUID tenantId) {
        return jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<List<String>>) con -> {
            try (var stmt = con.createStatement()) {
                stmt.execute("SELECT set_config('app.current_tenant', '" + tenantId + "', false)");
                var rs = stmt.executeQuery("SELECT override_reason FROM credit_overrides WHERE tenant_id = '" + tenantId + "' ORDER BY set_at");
                List<String> reasons = new java.util.ArrayList<>();
                while (rs.next()) {
                    reasons.add(rs.getString("override_reason"));
                }
                return reasons;
            }
        });
    }
}
