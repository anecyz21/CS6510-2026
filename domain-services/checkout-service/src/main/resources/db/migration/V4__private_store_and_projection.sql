-- Private store (004 research R2): the view that Analytics read across schemas is removed.
-- The accepted-scan outbox table that replaces it reuses the name in a later migration.
DROP VIEW accepted_scan_outbox;

-- The product name is captured with the price at the first scan of a SKU and never re-read.
ALTER TABLE basket_lines ADD COLUMN name VARCHAR(255) NOT NULL DEFAULT '';

-- Local, versioned, rebuildable copy of Catalog's active products (research R6).
CREATE TABLE catalog_projection (
  sku VARCHAR(64) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  price_cents BIGINT NOT NULL,
  catalog_version BIGINT NOT NULL,
  CONSTRAINT chk_projection_price_nonneg CHECK (price_cents >= 0)
);

-- catalog_version NULL means the projection was never loaded (EMPTY).
CREATE TABLE projection_state (
  id TINYINT PRIMARY KEY,
  catalog_version BIGINT NULL,
  loaded_at TIMESTAMP(6) NULL,
  last_verified_at TIMESTAMP(6) NULL,
  CONSTRAINT chk_projection_state_single_row CHECK (id = 1)
);
INSERT INTO projection_state (id, catalog_version, loaded_at, last_verified_at) VALUES (1, NULL, NULL, NULL);
