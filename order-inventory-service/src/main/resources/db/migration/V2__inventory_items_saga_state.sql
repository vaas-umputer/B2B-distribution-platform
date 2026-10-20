ALTER TABLE orders ADD COLUMN saga_state TEXT NOT NULL DEFAULT 'PLACED';
ALTER TABLE orders ADD COLUMN idempotency_key TEXT;
ALTER TABLE orders ADD COLUMN invoice_id UUID;
ALTER TABLE orders ADD COLUMN rejection_reason TEXT;
-- Scoped per tenant, not globally: two different tenants independently
-- choosing the same key string (e.g. both send "order-1") is none of the
-- other's business and shouldn't collide.
ALTER TABLE orders ADD CONSTRAINT orders_tenant_idempotency_key_unique UNIQUE (tenant_id, idempotency_key);

-- Shared catalog stock (the plan's "shared catalog" framing: every tenant
-- draws from the same inventory pool) - not tenant-owned, no RLS.
CREATE TABLE inventory (
    sku TEXT PRIMARY KEY,
    qty_available INT NOT NULL,
    qty_reserved INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);

INSERT INTO inventory (sku, qty_available, qty_reserved, version) VALUES
    ('SKU-001', 100, 0, 0),
    ('SKU-002', 500, 0, 0),
    ('SKU-003', 1000, 0, 0);

CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    sku TEXT NOT NULL,
    qty INT NOT NULL,
    unit_price NUMERIC NOT NULL
);

ALTER TABLE order_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_items FORCE ROW LEVEL SECURITY;
-- order_items has no tenant_id of its own (per the plan's schema) - isolation
-- is via its FK to orders, which has its own RLS policy already satisfied by
-- the same transaction's tenant context.
CREATE POLICY order_items_tenant_isolation ON order_items
    USING (order_id IN (SELECT id FROM orders WHERE tenant_id = current_setting('app.current_tenant', true)::uuid));
