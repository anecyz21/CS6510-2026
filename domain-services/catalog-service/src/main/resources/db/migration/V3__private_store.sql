-- Private store (004 research R2): nothing outside Catalog may read this database, so the view
-- formerly granted to Checkout is removed. Consumers use the internal v1 API instead.
DROP VIEW active_items;

-- Freshness probe for consumer projections (research R6). The version is incremented by any
-- migration or seed that changes item rows; this feature has no runtime catalog writes.
CREATE TABLE catalog_metadata (
  id TINYINT PRIMARY KEY,
  catalog_version BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT chk_catalog_metadata_single_row CHECK (id = 1),
  CONSTRAINT chk_catalog_version_positive CHECK (catalog_version >= 1)
);
INSERT INTO catalog_metadata (id, catalog_version, updated_at) VALUES (1, 1, CURRENT_TIMESTAMP(6));
