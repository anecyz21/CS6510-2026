-- Version-based outbox for catalog.products.v1 (005 research R2, data-model "Catalog"). Item rows change
-- only through migrations, which bump catalog_metadata.catalog_version; publication is pending exactly
-- while published_version is NULL or below that version. Only Catalog's ProductPublisher reads this.
CREATE TABLE catalog_publication (
  id TINYINT PRIMARY KEY,
  published_version BIGINT NULL,
  published_at TIMESTAMP(6) NULL,
  CONSTRAINT chk_catalog_publication_single_row CHECK (id = 1)
);
INSERT INTO catalog_publication (id, published_version, published_at) VALUES (1, NULL, NULL);
