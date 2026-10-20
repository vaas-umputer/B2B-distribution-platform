ALTER TABLE tenants ADD COLUMN username TEXT UNIQUE;
ALTER TABLE tenants ADD COLUMN password_hash TEXT;
ALTER TABLE tenants ADD COLUMN email TEXT;
ALTER TABLE tenants ADD COLUMN phone TEXT;
-- Existing Phase 1 demo tenants may not have credentials; new registration
-- always supplies both fields and legacy tenants cannot authenticate.
DROP POLICY tenants_self_select ON tenants;
CREATE POLICY tenants_self_select ON tenants FOR SELECT USING (
  id = current_setting('app.current_tenant', true)::uuid
  OR username = current_setting('app.login_username', true)
);

CREATE TABLE tenant_documents (
  id UUID PRIMARY KEY, tenant_id UUID NOT NULL, document_type TEXT NOT NULL,
  storage_key TEXT NOT NULL, original_filename TEXT NOT NULL, content_type TEXT,
  review_status TEXT NOT NULL DEFAULT 'PENDING', review_note TEXT,
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE tenant_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_documents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_documents_self ON tenant_documents USING (tenant_id = current_setting('app.current_tenant', true)::uuid) WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);
