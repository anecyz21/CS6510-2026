-- Event-driven completion and catalog projection (005 data-model "Checkout", research R3, R5).

-- Commands to Inventory, written in the same local transaction as the attempt transition that causes
-- them. Each send, recovery resends included, is its own row with its own message_id. published_at
-- is set only after the broker acknowledged the record; only Checkout's OutboxSender reads this table.
CREATE TABLE command_outbox (
  outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id CHAR(36) NOT NULL,
  attempt_id VARCHAR(64) NOT NULL,
  message_type VARCHAR(32) NOT NULL,
  payload JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  published_at TIMESTAMP(6) NULL,
  CONSTRAINT uq_command_outbox_message UNIQUE (message_id),
  CONSTRAINT chk_command_outbox_type CHECK (message_type IN ('ReserveRequested', 'CommitRequested', 'CancelRequested')),
  INDEX idx_command_outbox_pending (published_at, outbox_id),
  INDEX idx_command_outbox_attempt (attempt_id, published_at, outbox_id)
);

-- Replies drive the saga, so no driver holds an attempt: the lease and the step-failure count go.
-- last_command_at and command_sends drive the recovery resend of the current state's command.
ALTER TABLE completion_attempts
  ADD COLUMN last_command_at TIMESTAMP(6) NULL,
  ADD COLUMN command_sends INT NOT NULL DEFAULT 1;
UPDATE completion_attempts SET last_command_at = updated_at;
ALTER TABLE completion_attempts
  MODIFY COLUMN last_command_at TIMESTAMP(6) NOT NULL,
  DROP COLUMN step_failures,
  DROP COLUMN lease_owner,
  DROP COLUMN lease_until;

-- The projection is built from Catalog's product topic; topic_offset is the last applied record and is
-- written in the same transaction as the rows. NULL means replay from the beginning. The 004
-- staleness probe is gone; catalog_version now holds the highest applied version.
ALTER TABLE projection_state
  ADD COLUMN topic_offset BIGINT NULL,
  DROP COLUMN last_verified_at;
