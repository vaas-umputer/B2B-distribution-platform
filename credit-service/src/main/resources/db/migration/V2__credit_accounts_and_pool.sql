ALTER TABLE credit_locks ADD CONSTRAINT credit_locks_status_check
    CHECK (status IN ('LOCKED', 'RELEASED', 'CONSUMED'));

CREATE TABLE credit_accounts (
    tenant_id UUID PRIMARY KEY,
    credit_limit NUMERIC NOT NULL,
    outstanding NUMERIC NOT NULL DEFAULT 0,
    throttled BOOLEAN NOT NULL DEFAULT false,
    version INT NOT NULL DEFAULT 0
);

ALTER TABLE credit_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_accounts FORCE ROW LEVEL SECURITY;
CREATE POLICY credit_accounts_tenant_isolation ON credit_accounts
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- Platform-wide singleton (Imperial's own working-capital pool, not any one
-- tenant's data) - deliberately NOT RLS-protected. Still only ever touched
-- from inside a @TenantScoped caller's transaction in practice this phase,
-- but the row itself isn't tenant-owned, so a policy here would be
-- meaningless (there's no tenant_id column to check against).
CREATE TABLE pool_balance (
    id SMALLINT PRIMARY KEY DEFAULT 1,
    cash NUMERIC NOT NULL,
    committed_payables NUMERIC NOT NULL DEFAULT 0,
    operating_buffer NUMERIC NOT NULL DEFAULT 0,
    buffer_percent NUMERIC NOT NULL DEFAULT 0.15,
    total_outstanding NUMERIC NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT pool_balance_singleton CHECK (id = 1)
);

-- Seed values are a plausible demo-scale pool, not anything load-bearing:
-- AvailableWorkingCapital = 10,000,000 - 0 - 1,000,000 = 9,000,000
-- AllocatablePool = 9,000,000 * (1 - 0.15) = 7,650,000
INSERT INTO pool_balance (id, cash, committed_payables, operating_buffer, buffer_percent, total_outstanding, version)
VALUES (1, 10000000, 0, 1000000, 0.15, 0, 0);
