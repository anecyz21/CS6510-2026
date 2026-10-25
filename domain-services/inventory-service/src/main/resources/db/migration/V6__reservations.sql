-- Completion-time reservations owned by Inventory (004 data-model, research R8/R11).
-- attempt_id is Checkout's completion-attempt identity and the idempotency key of every command.
CREATE TABLE reservations (
  attempt_id VARCHAR(64) PRIMARY KEY,
  transaction_id VARCHAR(64) NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  resolved_at TIMESTAMP(6) NULL,
  CONSTRAINT chk_reservation_state CHECK (state IN ('RESERVED', 'COMMITTED', 'RELEASED', 'CANCELLED'))
);

-- 0 <= reserved <= requested for every line. A CANCELLED tombstone has no lines.
CREATE TABLE reservation_lines (
  attempt_id VARCHAR(64) NOT NULL,
  sku VARCHAR(64) NOT NULL,
  requested_quantity INT NOT NULL,
  reserved_quantity INT NOT NULL,
  PRIMARY KEY (attempt_id, sku),
  CONSTRAINT chk_reservation_requested CHECK (requested_quantity >= 1),
  CONSTRAINT chk_reservation_reserved CHECK (reserved_quantity >= 0 AND reserved_quantity <= requested_quantity)
);

-- Every committed decrement references a durable attempt; provisioning rows carry no attempt.
-- The unique key prevents a second SALE movement for the same attempt and SKU (NULLs never collide).
ALTER TABLE inventory_movements
  ADD COLUMN attempt_id VARCHAR(64) NULL,
  ADD COLUMN movement_type VARCHAR(16) NOT NULL DEFAULT 'SALE',
  RENAME COLUMN stock_after TO physical_after,
  ADD CONSTRAINT chk_movement_type CHECK (movement_type IN ('SALE', 'PROVISION')),
  ADD CONSTRAINT chk_sale_has_attempt CHECK (movement_type <> 'SALE' OR attempt_id IS NOT NULL),
  ADD UNIQUE KEY uq_sale_attempt_sku (attempt_id, sku);
