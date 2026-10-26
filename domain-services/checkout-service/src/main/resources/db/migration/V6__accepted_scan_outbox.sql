-- Transactional outbox for accepted scans (004 research R4, FR-020). Each row commits in the same
-- local transaction as the basket upsert; only Checkout's relay reads it. published_at is set only
-- after the broker has acknowledged the event. Development rows of the old table are not migrated.
DROP TABLE accepted_scan_events;

CREATE TABLE accepted_scan_outbox (
  outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id CHAR(36) NOT NULL,
  transaction_id VARCHAR(64) NOT NULL,
  station_id VARCHAR(255) NOT NULL,
  sku VARCHAR(64) NOT NULL,
  accepted_at TIMESTAMP(6) NOT NULL,
  published_at TIMESTAMP(6) NULL,
  CONSTRAINT uq_outbox_event UNIQUE (event_id),
  INDEX idx_outbox_pending (published_at, outbox_id),
  INDEX idx_outbox_transaction (transaction_id)
);
