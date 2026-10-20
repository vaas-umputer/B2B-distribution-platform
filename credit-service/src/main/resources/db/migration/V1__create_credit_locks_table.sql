CREATE TABLE credit_locks (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    amount NUMERIC NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE credit_locks ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_locks FORCE ROW LEVEL SECURITY;

CREATE POLICY credit_locks_tenant_isolation ON credit_locks
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);
