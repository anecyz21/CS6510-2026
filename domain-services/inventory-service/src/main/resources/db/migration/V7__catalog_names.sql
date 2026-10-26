-- Name-only catalog projection for the low-stock view (research R7), the same definitions as
-- Analytics V4. catalog_version NULL in projection_state means never loaded.
CREATE TABLE catalog_names (
  sku VARCHAR(64) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  catalog_version BIGINT NOT NULL
);
CREATE TABLE projection_state (
  id TINYINT PRIMARY KEY,
  catalog_version BIGINT NULL,
  loaded_at TIMESTAMP(6) NULL,
  last_verified_at TIMESTAMP(6) NULL,
  CONSTRAINT chk_projection_state_single_row CHECK (id = 1)
);
INSERT INTO projection_state (id, catalog_version, loaded_at, last_verified_at) VALUES (1, NULL, NULL, NULL);

-- The low-stock view looks up each alerted SKU's latest sale below the threshold.
CREATE INDEX idx_movements_sku_type ON inventory_movements (sku, movement_type, occurred_at);
