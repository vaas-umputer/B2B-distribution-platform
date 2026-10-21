package com.imperial.credit.concurrency;

import com.imperial.common.dto.LockCreditResponse;
import com.imperial.common.tenant.TenantContext;
import com.imperial.credit.CreditServiceApplication;
import com.imperial.credit.service.CreditCheckService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan's own words: "The one test that matters most." A pool with a
 * known, small AllocatablePool; N concurrent lockCredit calls whose
 * combined requested value exceeds it; assert the sum of APPROVED locks'
 * values never exceeds the pool, and that the approved count is exactly
 * what SELECT ... FOR UPDATE serialization should allow. Any flake here is
 * a real locking bug, not test flakiness - it's the entire reason this
 * architecture (pessimistic row locks, pool-then-account ordering) exists
 * instead of just incrementing a counter.
 */
@Testcontainers
@SpringBootTest(classes = CreditServiceApplication.class, properties = "spring.flyway.enabled=false")
class CreditConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("credit_db");

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final BigDecimal TENANT_LIMIT = new BigDecimal("1000000"); // never the binding constraint
    private static final BigDecimal POOL_CASH = new BigDecimal("10000");
    private static final int CONCURRENT_REQUESTS = 20;
    private static final BigDecimal REQUEST_VALUE = new BigDecimal("1000"); // 20 * 1000 = 20,000 >> pool of 10,000

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
    static void createSchemaRoleAndSeedData() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = admin.createStatement()) {

            stmt.execute("CREATE ROLE app_user LOGIN PASSWORD 'app_user'");
            stmt.execute("GRANT ALL ON SCHEMA public TO app_user");

            // Minimal schema needed by this test - a trimmed copy of this
            // service's own Flyway migrations (Flyway itself is disabled
            // for this context; see the class-level @SpringBootTest, same
            // reasoning as Phase 0/1's RlsConcurrencyTest).
            stmt.execute("""
                    CREATE TABLE credit_accounts (
                        tenant_id UUID PRIMARY KEY,
                        credit_limit NUMERIC NOT NULL,
                        outstanding NUMERIC NOT NULL DEFAULT 0,
                        throttled BOOLEAN NOT NULL DEFAULT false,
                        version INT NOT NULL DEFAULT 0
                    )""");
            stmt.execute("ALTER TABLE credit_accounts ENABLE ROW LEVEL SECURITY");
            stmt.execute("ALTER TABLE credit_accounts FORCE ROW LEVEL SECURITY");
            stmt.execute("""
                    CREATE POLICY credit_accounts_tenant_isolation ON credit_accounts
                        USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
                        WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid)
                    """);

            stmt.execute("""
                    CREATE TABLE pool_balance (
                        id SMALLINT PRIMARY KEY DEFAULT 1,
                        cash NUMERIC NOT NULL,
                        committed_payables NUMERIC NOT NULL DEFAULT 0,
                        operating_buffer NUMERIC NOT NULL DEFAULT 0,
                        buffer_percent NUMERIC NOT NULL DEFAULT 0,
                        total_outstanding NUMERIC NOT NULL DEFAULT 0,
                        version INT NOT NULL DEFAULT 0
                    )""");

            stmt.execute("""
                    CREATE TABLE credit_locks (
                        id UUID PRIMARY KEY,
                        tenant_id UUID NOT NULL,
                        order_id UUID NOT NULL,
                        amount NUMERIC NOT NULL,
                        status TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("ALTER TABLE credit_locks ENABLE ROW LEVEL SECURITY");
            stmt.execute("ALTER TABLE credit_locks FORCE ROW LEVEL SECURITY");
            stmt.execute("""
                    CREATE POLICY credit_locks_tenant_isolation ON credit_locks
                        USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
                        WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid)
                    """);

            // Not used by this test's scenario, but it's a mapped @Entity -
            // Hibernate's ddl-auto=validate checks every entity's table
            // exists at context startup, regardless of whether this test
            // exercises it.
            stmt.execute("""
                    CREATE TABLE credit_risk_scores (
                        tenant_id UUID PRIMARY KEY,
                        declared_volume NUMERIC NOT NULL,
                        business_vintage INT NOT NULL,
                        collateral NUMERIC NOT NULL,
                        relationship_category INT NOT NULL,
                        computed_score INT NOT NULL,
                        suggested_limit NUMERIC NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("ALTER TABLE credit_risk_scores ENABLE ROW LEVEL SECURITY");
            stmt.execute("ALTER TABLE credit_risk_scores FORCE ROW LEVEL SECURITY");
            stmt.execute("""
                    CREATE POLICY credit_risk_scores_tenant_isolation ON credit_risk_scores
                        USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
                        WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid)
                    """);

            stmt.execute("""
                    CREATE TABLE processed_operations (
                        operation_key TEXT PRIMARY KEY,
                        processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("""
                    CREATE TABLE credit_overrides (
                        id UUID PRIMARY KEY,
                        tenant_id UUID NOT NULL,
                        old_limit NUMERIC,
                        new_limit NUMERIC NOT NULL,
                        override_reason TEXT NOT NULL,
                        set_by TEXT NOT NULL,
                        set_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("ALTER TABLE credit_overrides ENABLE ROW LEVEL SECURITY");
            stmt.execute("ALTER TABLE credit_overrides FORCE ROW LEVEL SECURITY");
            stmt.execute("""
                    CREATE POLICY credit_overrides_tenant_isolation ON credit_overrides
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
                    CREATE POLICY limit_increase_requests_tenant_isolation ON limit_increase_requests
                        USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
                        WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid)
                    """);

            // The superuser created every table above; app_user needs
            // explicit table-level grants too - schema-level GRANT ALL
            // (already given) only covers CREATE/USAGE on the schema
            // itself, not privileges on tables someone else already made.
            stmt.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO app_user");

            // Known, small, deliberately-exceedable pool: buffer_percent=0,
            // operating_buffer=0, so AllocatablePool == cash exactly.
            stmt.execute("INSERT INTO pool_balance (id, cash) VALUES (1, " + POOL_CASH + ")");
            stmt.execute("INSERT INTO credit_accounts (tenant_id, credit_limit) VALUES ('" + TENANT_ID + "', " + TENANT_LIMIT + ")");
        }
    }

    @Autowired
    private CreditCheckService creditCheckService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Run many times per the plan ("50+ in CI") - a flake on ANY repetition is the architecture failing to do its job. */
    @RepeatedTest(50)
    void concurrentLocksNeverOverdrawThePool() throws Exception {
        resetBalancesBetweenRepetitions();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            List<Callable<LockCreditResponse>> tasks = java.util.stream.IntStream.range(0, CONCURRENT_REQUESTS)
                    .<Callable<LockCreditResponse>>mapToObj(i -> () -> {
                        TenantContext.set(TENANT_ID);
                        try {
                            return creditCheckService.lockCredit(TENANT_ID, UUID.randomUUID(), REQUEST_VALUE);
                        } finally {
                            TenantContext.clear();
                        }
                    })
                    .toList();

            List<Future<LockCreditResponse>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);

            AtomicInteger approvedCount = new AtomicInteger();
            BigDecimal approvedTotal = BigDecimal.ZERO;
            for (Future<LockCreditResponse> future : futures) {
                LockCreditResponse response = future.get();
                if (response.approved()) {
                    approvedCount.incrementAndGet();
                    approvedTotal = approvedTotal.add(REQUEST_VALUE);
                }
            }

            // Pool is 10,000; each request is 1,000 -> at most 10 can ever be approved.
            assertThat(approvedTotal).isLessThanOrEqualTo(POOL_CASH);
            assertThat(approvedCount.get()).isEqualTo(10);

            BigDecimal actualTotalOutstanding = jdbcTemplate.queryForObject(
                    "SELECT total_outstanding FROM pool_balance WHERE id = 1", BigDecimal.class);
            assertThat(actualTotalOutstanding).isEqualByComparingTo(approvedTotal);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * This cleanup runs OUTSIDE any @TenantScoped method, on a connection
     * HikariCP may hand back from a previous repetition. Once this test's
     * tenant id has ever been set via set_config on that physical
     * connection, Postgres's placeholder semantics for a custom GUC mean
     * current_setting no longer reads as NULL there - see TenantRlsAspect's
     * Javadoc ("A real Postgres gotcha"). Setting it explicitly first
     * (is_local=false, session-scoped - fine here since this connection is
     * about to be reused for the SAME tenant throughout the next
     * repetition anyway) is this test's own fix, not a change to the
     * aspect real request-handling code relies on.
     */
    private void resetBalancesBetweenRepetitions() {
        // All 4 statements run on the SAME physical connection on purpose
        // (one ConnectionCallback), so the set_config above is guaranteed
        // to apply to the same connection the RLS-protected statements
        // after it run on - HikariCP usually hands one thread the same
        // connection back-to-back, but "usually" isn't a guarantee this
        // fix should depend on.
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            try (var stmt = con.createStatement()) {
                stmt.execute("SELECT set_config('app.current_tenant', '" + TENANT_ID + "', false)");
                stmt.execute("UPDATE pool_balance SET total_outstanding = 0 WHERE id = 1");
                stmt.execute("UPDATE credit_accounts SET outstanding = 0 WHERE tenant_id = '" + TENANT_ID + "'");
                stmt.execute("DELETE FROM credit_locks WHERE tenant_id = '" + TENANT_ID + "'");
            }
            return null;
        });
    }
}
