-- Idempotency guard (see common's IdempotencyGuard). Keyed by an opaque
-- operation string, not by tenant - no RLS, and nothing request-scoped ever
-- needs to read it back.
CREATE TABLE processed_operations (
    operation_key TEXT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Admin-facing and inherently cross-tenant (an admin dashboard needs every
-- tenant's alerts in one place) - no RLS. Written only by the near-limit
-- scheduled job, via svc_system (see 01-create-databases-and-role.sh).
CREATE TABLE alerts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    utilization NUMERIC NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE monthly_reports (
    id UUID PRIMARY KEY,
    report_month DATE NOT NULL,
    near_limit_tenant_count INT NOT NULL,
    pool_utilization_percent NUMERIC NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
