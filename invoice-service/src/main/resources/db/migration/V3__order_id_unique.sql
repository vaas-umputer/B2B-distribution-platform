-- Prevents a duplicated issueInvoice call (a Feign retry from Order &
-- Inventory Service after a timed-out-but-actually-succeeded request, for
-- example) from creating two invoices for the same order. The application
-- layer also checks for an existing invoice by order_id before inserting -
-- this constraint is the real guarantee the same way credit_locks' own
-- idempotency relies on its order_id lookup plus the app-level check, not
-- the constraint alone (a straight UNIQUE violation would otherwise surface
-- as an uncaught 500 under concurrent duplicate requests).
ALTER TABLE invoices ADD CONSTRAINT invoices_order_id_unique UNIQUE (order_id);
