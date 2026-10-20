package com.imperial.credit.concurrency;

import com.imperial.common.tenant.TenantContext;
import com.imperial.credit.CreditServiceApplication;
import com.imperial.credit.service.CreditCheckService;
import org.junit.jupiter.api.AfterEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Credit Service's own idempotency guarantee: a retried lockCredit for the
 * SAME orderId must never double-lock. This is scoped to Credit Service
 * alone (not a full Order+Credit+Invoice integration test) - see the
 * build's own notes on why Phase 1/2 settled for per-service idempotency
 * tests plus manual end-to-end verification rather than standing up all
 * three Spring contexts in one test.
 */
@Testcontainers
@SpringBootTest(classes = CreditServiceApplication.class, properties = "spring.flyway.enabled=false")
class CreditIdempotencyTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("credit_db");

    private static final UUID TENANT_ID = UUID.randomUUID();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_user");
        registry.add("spring.datasource.password", () -> "app_user");
        registry.add("system.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("system.datasource.username", () -> "app_user");
        registry.add("system.datasource.password", () -> "app_user");
    }

    @BeforeAll
    static void createSchemaAndRole() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = admin.createStatement()) {

            stmt.execute("CREATE ROLE app_user LOGIN PASSWORD 'app_user'");
            stmt.execute("GRANT ALL ON SCHEMA public TO app_user");

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

            stmt.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO app_user");
            stmt.execute("INSERT INTO pool_balance (id, cash) VALUES (1, 1000000)");
            stmt.execute("INSERT INTO credit_accounts (tenant_id, credit_limit) VALUES ('" + TENANT_ID + "', 500000)");
        }
    }

    @Autowired
    private CreditCheckService creditCheckService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setTenant() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void retriedLockForSameOrder_resultsInExactlyOneLockAndOneDebit() {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1000");

        var first = creditCheckService.lockCredit(TENANT_ID, orderId, amount);
        var second = creditCheckService.lockCredit(TENANT_ID, orderId, amount); // retry, same orderId
        var third = creditCheckService.lockCredit(TENANT_ID, orderId, amount);  // retry again

        assertThat(first.approved()).isTrue();
        assertThat(second.lockId()).isEqualTo(first.lockId());
        assertThat(third.lockId()).isEqualTo(first.lockId());

        State state = readState(orderId);
        assertThat(state.lockCount).isEqualTo(1);
        assertThat(state.outstanding).isEqualByComparingTo(amount); // debited exactly once, not three times
        assertThat(state.totalOutstanding).isEqualByComparingTo(amount);
    }

    @Test
    void retriedReleaseForSameLock_isANoOpAfterTheFirstCall() {
        UUID orderId = UUID.randomUUID();
        var lockResponse = creditCheckService.lockCredit(TENANT_ID, orderId, new BigDecimal("1000"));

        creditCheckService.release(lockResponse.lockId());
        creditCheckService.release(lockResponse.lockId()); // retry
        creditCheckService.release(lockResponse.lockId()); // retry again

        State state = readState(orderId);
        assertThat(state.outstanding).isEqualByComparingTo(BigDecimal.ZERO); // credited back exactly once, not three times
    }

    /**
     * All three reads happen on ONE connection (ConnectionCallback) with
     * app.current_tenant pinned first, for the same reason the
     * concurrency test's cleanup does - see TenantRlsAspect's Javadoc on
     * the Postgres placeholder-GUC gotcha. These SELECTs are the test's
     * own assertions, not production code, so they don't go through
     * @TenantScoped.
     */
    private State readState(UUID orderId) {
        return jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<State>) con -> {
            try (var stmt = con.createStatement()) {
                stmt.execute("SELECT set_config('app.current_tenant', '" + TENANT_ID + "', false)");
                var rs = stmt.executeQuery("SELECT count(*) AS n FROM credit_locks WHERE order_id = '" + orderId + "'");
                rs.next();
                int lockCount = rs.getInt("n");

                rs = stmt.executeQuery("SELECT outstanding FROM credit_accounts WHERE tenant_id = '" + TENANT_ID + "'");
                rs.next();
                BigDecimal outstanding = rs.getBigDecimal("outstanding");

                rs = stmt.executeQuery("SELECT total_outstanding FROM pool_balance WHERE id = 1");
                rs.next();
                BigDecimal totalOutstanding = rs.getBigDecimal("total_outstanding");

                return new State(lockCount, outstanding, totalOutstanding);
            }
        });
    }

    private record State(int lockCount, BigDecimal outstanding, BigDecimal totalOutstanding) {
    }
}
