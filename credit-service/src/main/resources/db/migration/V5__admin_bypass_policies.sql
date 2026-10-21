-- svc_system no longer has BYPASSRLS (see infra/postgres-init's updated
-- comment - not grantable on a fully-managed Postgres host). These
-- policies restore the same effective cross-tenant admin access through
-- an ordinary session-local flag instead: Postgres combines multiple
-- PERMISSIVE policies with OR, so this is purely additive and leaves
-- every existing policy on these tables untouched. Only credit_accounts
-- and limit_increase_requests are ever read through the svc_system
-- connection (AdminReadService, CreditScheduledJobs,
-- LimitIncreaseRequestService) - credit_locks/credit_risk_scores/
-- credit_overrides never are, so they're left alone.
CREATE POLICY credit_accounts_admin_bypass ON credit_accounts
    USING (current_setting('app.admin_mode', true) = 'true')
    WITH CHECK (current_setting('app.admin_mode', true) = 'true');

CREATE POLICY limit_increase_requests_admin_bypass ON limit_increase_requests
    USING (current_setting('app.admin_mode', true) = 'true')
    WITH CHECK (current_setting('app.admin_mode', true) = 'true');
