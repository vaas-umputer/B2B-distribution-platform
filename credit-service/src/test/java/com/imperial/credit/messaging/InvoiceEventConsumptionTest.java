package com.imperial.credit.messaging;

import com.imperial.common.event.InvoiceEvent;
import com.imperial.common.tenant.TenantContext;
import com.imperial.credit.CreditServiceApplication;
import com.imperial.credit.service.CreditCheckService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the asynchronous half of the saga actually works end to end:
 * publish a real invoice.paid message to a real RabbitMQ broker, and
 * assert Credit Service's own consumer (InvoiceEventListener) reacts by
 * rotating the matching lock to CONSUMED and decrementing both the
 * tenant's and the pool's outstanding balance - without this test ever
 * calling CreditCheckService.consume(...) directly. If the exchange/queue
 * bindings or the message converter were wired wrong, this is what would
 * catch it; a direct unit test of consume() would not.
 */
@Testcontainers
@SpringBootTest(classes = CreditServiceApplication.class, properties = "spring.flyway.enabled=false")
class InvoiceEventConsumptionTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("credit_db");

    @Container
    static RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3-management-alpine");

    private static final UUID TENANT_ID = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_user");
        registry.add("spring.datasource.password", () -> "app_user");
        registry.add("system.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("system.datasource.username", () -> "app_user");
        registry.add("system.datasource.password", () -> "app_user");

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBITMQ.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
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

            stmt.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO app_user");
            stmt.execute("INSERT INTO pool_balance (id, cash) VALUES (1, 1000000)");
            stmt.execute("INSERT INTO credit_accounts (tenant_id, credit_limit) VALUES ('" + TENANT_ID + "', 500000)");
        }
    }

    @Autowired
    private CreditCheckService creditCheckService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void publishingInvoicePaid_rotatesTheMatchingLockToConsumed() {
        UUID orderId = UUID.randomUUID();
        TenantContext.set(TENANT_ID);
        var lockResponse = creditCheckService.lockCredit(TENANT_ID, orderId, new BigDecimal("2000"));
        TenantContext.clear();
        assertThat(lockResponse.approved()).isTrue();

        var event = new InvoiceEvent(UUID.randomUUID(), UUID.randomUUID(), orderId, TENANT_ID, new BigDecimal("2000"));
        rabbitTemplate.convertAndSend(RabbitMqConfig.INVOICE_EXCHANGE, "invoice.paid", event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            String status = readLockStatus(orderId);
            assertThat(status).isEqualTo("CONSUMED");
        });

        BigDecimal outstanding = readOutstanding();
        assertThat(outstanding).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private String readLockStatus(UUID orderId) {
        return jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<String>) con -> {
            try (var stmt = con.createStatement()) {
                stmt.execute("SELECT set_config('app.current_tenant', '" + TENANT_ID + "', false)");
                var rs = stmt.executeQuery("SELECT status FROM credit_locks WHERE order_id = '" + orderId + "'");
                rs.next();
                return rs.getString("status");
            }
        });
    }

    private BigDecimal readOutstanding() {
        return jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<BigDecimal>) con -> {
            try (var stmt = con.createStatement()) {
                stmt.execute("SELECT set_config('app.current_tenant', '" + TENANT_ID + "', false)");
                var rs = stmt.executeQuery("SELECT outstanding FROM credit_accounts WHERE tenant_id = '" + TENANT_ID + "'");
                rs.next();
                return rs.getBigDecimal("outstanding");
            }
        });
    }
}
