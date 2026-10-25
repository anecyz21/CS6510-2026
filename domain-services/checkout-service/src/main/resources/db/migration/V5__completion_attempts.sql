-- Completion saga state owned by Checkout (004 data-model, research R8/R10/R13).

-- active_attempt_id is the freeze and busy flag: non-null while one unresolved attempt owns the basket.
ALTER TABLE checkout_transactions
  ADD COLUMN active_attempt_id VARCHAR(64) NULL,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE completion_attempts (
  attempt_id VARCHAR(64) PRIMARY KEY,
  transaction_id VARCHAR(64) NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  finalized_at TIMESTAMP(6) NULL,
  step_failures INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP(6) NOT NULL,
  lease_owner VARCHAR(64) NULL,
  lease_until TIMESTAMP(6) NULL,
  needs_attention BOOLEAN NOT NULL DEFAULT FALSE,
  last_error VARCHAR(512) NULL,
  INDEX idx_attempt_transaction (transaction_id),
  INDEX idx_attempt_recovery (state, next_retry_at),
  CONSTRAINT chk_attempt_state CHECK (state IN ('RESERVING', 'COMMITTING', 'CANCELLING', 'FINALIZED', 'REJECTED', 'CANCELLED'))
);

-- The immutable basket snapshot of one attempt. reserved_quantity is the durable receipt intent.
CREATE TABLE completion_attempt_lines (
  attempt_id VARCHAR(64) NOT NULL,
  sku VARCHAR(64) NOT NULL,
  requested_quantity INT NOT NULL,
  unit_price_cents BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  reserved_quantity INT NULL,
  PRIMARY KEY (attempt_id, sku),
  CONSTRAINT chk_attempt_requested CHECK (requested_quantity > 0),
  CONSTRAINT chk_attempt_reserved CHECK (reserved_quantity IS NULL OR (reserved_quantity >= 0 AND reserved_quantity <= requested_quantity))
);

-- Receipts are re-keyed by attempt: one receipt per successful attempt, many per transaction.
-- Development data in the transaction-keyed tables is not migrated (R13).
DROP TABLE receipt_lines;
DROP TABLE receipts;

CREATE TABLE receipts (
  attempt_id VARCHAR(64) PRIMARY KEY,
  transaction_id VARCHAR(64) NOT NULL,
  completed_at TIMESTAMP(6) NOT NULL,
  INDEX idx_receipt_transaction (transaction_id)
);

CREATE TABLE receipt_lines (
  attempt_id VARCHAR(64) NOT NULL,
  sku VARCHAR(64) NOT NULL,
  quantity INT NOT NULL,
  unit_price_cents BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  PRIMARY KEY (attempt_id, sku),
  CONSTRAINT chk_receipt_line_quantity CHECK (quantity > 0),
  CONSTRAINT chk_receipt_line_price CHECK (unit_price_cents >= 0)
);
