CREATE TABLE credit_risk_scores (
    tenant_id UUID PRIMARY KEY,
    declared_volume NUMERIC NOT NULL,
    business_vintage INT NOT NULL,
    collateral NUMERIC NOT NULL,
    relationship_category INT NOT NULL,
    computed_score INT NOT NULL,
    suggested_limit NUMERIC NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE credit_risk_scores ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_risk_scores FORCE ROW LEVEL SECURITY;
CREATE POLICY credit_risk_scores_tenant_isolation ON credit_risk_scores
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- Schema only this phase - Phase 3 builds the self-service request + admin
-- review endpoints against this table.
CREATE TABLE limit_increase_requests (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    requested_amount NUMERIC NOT NULL,
    reason TEXT,
    status TEXT NOT NULL,
    reviewed_by TEXT,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE limit_increase_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE limit_increase_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY limit_increase_requests_tenant_isolation ON limit_increase_requests
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- Append-only audit trail: every limit change, however it happened, lands
-- one row here with who/why/when. Never updated or deleted.
CREATE TABLE credit_overrides (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    old_limit NUMERIC,
    new_limit NUMERIC NOT NULL,
    override_reason TEXT NOT NULL,
    set_by TEXT NOT NULL,
    set_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE credit_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_overrides FORCE ROW LEVEL SECURITY;
CREATE POLICY credit_overrides_tenant_isolation ON credit_overrides
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);
