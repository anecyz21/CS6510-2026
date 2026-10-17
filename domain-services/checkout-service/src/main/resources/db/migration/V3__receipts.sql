CREATE TABLE receipts (
  transaction_id VARCHAR(64) PRIMARY KEY,
  completed_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT receipt_transaction_fk FOREIGN KEY (transaction_id) REFERENCES checkout_transactions(transaction_id)
);

CREATE TABLE receipt_lines (
  transaction_id VARCHAR(64) NOT NULL,
  sku VARCHAR(64) NOT NULL,
  quantity INT NOT NULL,
  unit_price_cents BIGINT NOT NULL,
  PRIMARY KEY (transaction_id, sku),
  CONSTRAINT receipt_line_transaction_fk FOREIGN KEY (transaction_id) REFERENCES receipts(transaction_id),
  CHECK (quantity > 0),
  CHECK (unit_price_cents >= 0)
);
