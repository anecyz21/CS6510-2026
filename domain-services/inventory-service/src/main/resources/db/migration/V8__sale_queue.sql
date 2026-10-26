-- Committed sales waiting to be applied to inventory_positions (completion-bottleneck fix, tier 2).
-- A reservation commit writes one row per held line in the same local transaction that marks the
-- reservation COMMITTED; it touches no position row. SaleApplier then moves the quantities from
-- reserved_stock out of physical_stock in batches (one position update per SKU per batch) and writes
-- the SALE movements. Until then the quantity stays in reserved_stock, so availability is exact.
CREATE TABLE sale_queue (
  queue_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  attempt_id VARCHAR(64) NOT NULL,
  sku VARCHAR(64) NOT NULL,
  quantity INT NOT NULL,
  committed_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_sale_queue_attempt_sku UNIQUE (attempt_id, sku),
  CONSTRAINT chk_sale_queue_quantity CHECK (quantity >= 1)
);
