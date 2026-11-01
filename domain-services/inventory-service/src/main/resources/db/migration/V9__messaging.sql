-- Reservation commands arrive from inventory.commands.v1 and are answered on inventory.replies.v1
-- (005 data-model "Inventory", research R3, R4).

-- One reply per processed command, committed in the same local transaction as the reservation change
-- it reports. published_at is set only after the broker acknowledged the record; only Inventory's
-- OutboxSender reads this table.
CREATE TABLE reply_outbox (
  outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id CHAR(36) NOT NULL,
  causation_id CHAR(36) NOT NULL,
  attempt_id VARCHAR(64) NOT NULL,
  message_type VARCHAR(32) NOT NULL,
  payload JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  published_at TIMESTAMP(6) NULL,
  CONSTRAINT uq_reply_outbox_message UNIQUE (message_id),
  CONSTRAINT chk_reply_outbox_type CHECK (message_type IN ('Reserved', 'Committed', 'Released', 'Cancelled', 'Conflict')),
  INDEX idx_reply_outbox_pending (published_at, outbox_id),
  INDEX idx_reply_outbox_attempt (attempt_id, published_at, outbox_id)
);

-- The name projection is built from Catalog's product topic; see Checkout V7 for the same change.
ALTER TABLE projection_state
  ADD COLUMN topic_offset BIGINT NULL,
  DROP COLUMN last_verified_at;
