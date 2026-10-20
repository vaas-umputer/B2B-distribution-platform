ALTER TABLE invoices ADD COLUMN due_date DATE;
UPDATE invoices SET due_date = (created_at + INTERVAL '30 days')::date WHERE due_date IS NULL;
ALTER TABLE invoices ALTER COLUMN due_date SET NOT NULL;
