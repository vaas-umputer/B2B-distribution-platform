package com.imperial.tenant.rls;

import com.imperial.common.tenant.TenantContext;
import com.imperial.tenant.TenantCatalogServiceApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0's one real technical risk, burned down here: HikariCP reuses
 * physical connections across requests, and a naive "SET app.current_tenant"
 * on checkout can leak a previous request's tenant into the next one that
 * happens to reuse the same pooled connection. This test proves the fix
 * (SET LOCAL via set_config, run inside the same transaction as the query,
 * by TenantRlsAspect) actually holds under real concurrency - not just once,
 * but across many interleaved threads and iterations, since a connection-pool
 * leak is exactly the kind of bug that passes on a single run and fails under
 * load.
 *
 * <p>The Postgres superuser bypasses RLS entirely, so the test deliberately
 * runs the app against a separate, non-superuser "app_user" role created
 * below - using the container's default superuser for setup only. Without
 * this split, the policy below could be silently doing nothing and the test
 * would still pass.
 *
 * <p>Flyway is disabled for this context: this test creates its own
 * throwaway poc_items table directly (see createSchemaAndRestrictedRole
 * below), which Flyway - now that Phase 1 added real migrations to this
 * app - would otherwise refuse to run against, since it finds a non-empty
 * "public" schema with no schema_history table yet.
 */
@Testcontainers
@SpringBootTest(
        classes = TenantCatalogServiceApplication.class,
        properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none"})
class RlsConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tenant_db");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_user");
        registry.add("spring.datasource.password", () -> "app_user");
    }

    @BeforeAll
    static void createSchemaAndRestrictedRole() throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = admin.createStatement()) {

            stmt.execute("""
                    CREATE TABLE poc_items (
                        id UUID PRIMARY KEY,
                        tenant_id UUID NOT NULL,
                        value TEXT NOT NULL
                    )""");
            stmt.execute("ALTER TABLE poc_items ENABLE ROW LEVEL SECURITY");
            stmt.execute("""
                    CREATE POLICY tenant_isolation ON poc_items
                    USING (tenant_id = current_setting('app.current_tenant')::uuid)
                    """);

            // Non-superuser role: superusers bypass RLS, so the app must never
            // connect as one once RLS is meant to be the real enforcement layer.
            stmt.execute("CREATE ROLE app_user LOGIN PASSWORD 'app_user'");
            stmt.execute("GRANT SELECT, INSERT ON poc_items TO app_user");
        }
    }

    @AfterAll
    static void verifyPolicyRegistered() throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = admin.createStatement()) {
            var rs = stmt.executeQuery(
                    "SELECT policyname FROM pg_policies WHERE tablename = 'poc_items'");
            assertThat(rs.next()).as("tenant_isolation policy must be registered on poc_items").isTrue();
            assertThat(rs.getString("policyname")).isEqualTo("tenant_isolation");
        }
    }

    @Autowired
    private PocItemService pocItemService;

    @RepeatedTest(100)
    void concurrentTenantsNeverSeeEachOthersRows() throws InterruptedException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(4);

        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(
                    tenantTask(tenantA),
                    tenantTask(tenantB),
                    tenantTask(tenantA),
                    tenantTask(tenantB)
            ));

            for (Future<Boolean> result : results) {
                try {
                    assertThat(result.get(10, TimeUnit.SECONDS))
                            .as("a thread saw a row belonging to a different tenant")
                            .isTrue();
                } catch (Exception e) {
                    throw new AssertionError("tenant task failed", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * One thread's unit of work: set this thread's tenant, insert a row under
     * it, read the table back, and assert every row visible belongs to this
     * tenant. TenantContext is thread-local, so each worker thread owns its
     * own value - this is exactly the scenario a leaking pooled connection
     * would break.
     */
    private Callable<Boolean> tenantTask(UUID tenantId) {
        return () -> {
            try {
                TenantContext.set(tenantId);
                pocItemService.insert(UUID.randomUUID(), tenantId, "value-for-" + tenantId);
                List<PocItemRow> visible = pocItemService.findAll();
                return visible.stream().allMatch(row -> row.tenantId().equals(tenantId));
            } finally {
                TenantContext.clear();
            }
        };
    }

    @Test
    void sanityCheckContainerIsUp() {
        assertThat(POSTGRES.isRunning()).isTrue();
    }
}
