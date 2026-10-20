CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    status TEXT NOT NULL,
    credit_limit NUMERIC,
    risk_score INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
-- The app connects as app_user, which OWNS this table (it created it via
-- this very migration) - and table owners are exempt from RLS by default,
-- the same way superusers are. FORCE closes that loophole so the policies
-- below actually apply to the app's own connection, not just to some other,
-- less-privileged role we aren't using.
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;

-- A tenant may only ever read its own row. current_setting(..., true) with
-- missing_ok=true returns NULL instead of throwing when no tenant context is
-- set; NULL = id is never true, so an unauthenticated connection sees zero
-- rows - fail closed, not fail open.
CREATE POLICY tenants_self_select ON tenants
    FOR SELECT
    USING (id = current_setting('app.current_tenant', true)::uuid);

-- Onboarding happens before the tenant has any session/tenant context at
-- all (there's no token yet - this call is what creates the identity a
-- token will later be issued for), so INSERT is intentionally unrestricted
-- at the row-security layer. The real guard against abuse here is
-- API-level (rate limiting, review workflow) in later phases, not RLS -
-- RLS's job is isolating tenants from each other's data, not gatekeeping
-- who may become a tenant.
CREATE POLICY tenants_onboarding_insert ON tenants
    FOR INSERT
    WITH CHECK (true);
