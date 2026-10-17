CREATE VIEW accepted_scan_outbox AS
SELECT scan_id, transaction_id, sku, accepted_at
FROM checkout.accepted_scan_events;
