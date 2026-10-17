CREATE TABLE catalog_items (sku VARCHAR(64) PRIMARY KEY, name VARCHAR(255) NOT NULL, price_cents BIGINT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, CHECK (price_cents >= 0));
CREATE VIEW active_items AS SELECT sku, name, price_cents FROM catalog_items WHERE active = TRUE;
