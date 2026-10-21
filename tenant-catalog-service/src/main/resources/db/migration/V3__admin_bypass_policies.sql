-- svc_system no longer has BYPASSRLS (see infra/postgres-init's updated
-- comment - not grantable on a fully-managed Postgres host). These
-- policies restore the same effective cross-tenant admin access through
-- an ordinary session-local flag instead: Postgres combines multiple
-- PERMISSIVE policies with OR, so this is purely additive and leaves
-- every existing policy on these tables untouched.
CREATE POLICY tenants_admin_bypass ON tenants
    USING (current_setting('app.admin_mode', true) = 'true')
    WITH CHECK (current_setting('app.admin_mode', true) = 'true');

CREATE POLICY tenant_documents_admin_bypass ON tenant_documents
    USING (current_setting('app.admin_mode', true) = 'true')
    WITH CHECK (current_setting('app.admin_mode', true) = 'true');
