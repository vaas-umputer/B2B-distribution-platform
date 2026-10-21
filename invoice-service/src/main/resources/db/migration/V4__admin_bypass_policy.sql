-- svc_system no longer has BYPASSRLS (see infra/postgres-init's updated
-- comment - not grantable on a fully-managed Postgres host). This policy
-- restores the same effective cross-tenant read access (OverdueSweepJob's
-- discovery query) through an ordinary session-local flag instead:
-- Postgres combines multiple PERMISSIVE policies with OR, so this is
-- purely additive and leaves the existing policy on this table untouched.
CREATE POLICY invoices_admin_bypass ON invoices
    USING (current_setting('app.admin_mode', true) = 'true')
    WITH CHECK (current_setting('app.admin_mode', true) = 'true');
