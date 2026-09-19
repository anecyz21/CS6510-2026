CREATE TABLE catalog_items (
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    price_cents BIGINT NOT NULL,
    PRIMARY KEY (sku),
    CONSTRAINT chk_catalog_price_nonnegative CHECK (price_cents >= 0)
) ENGINE=InnoDB;

CREATE TABLE inventory (
    sku VARCHAR(64) NOT NULL,
    initial_stock INT NOT NULL,
    stock INT NOT NULL,
    reserved INT NOT NULL DEFAULT 0,
    low_stock_since TIMESTAMP NULL,
    PRIMARY KEY (sku),
    CONSTRAINT fk_inventory_catalog FOREIGN KEY (sku) REFERENCES catalog_items (sku),
    CONSTRAINT chk_inventory_nonnegative CHECK (initial_stock >= 0 AND stock >= 0 AND reserved >= 0),
    CONSTRAINT chk_inventory_reserved_stock CHECK (reserved <= stock)
) ENGINE=InnoDB;

CREATE TABLE checkout_transactions (
    transaction_id VARCHAR(64) NOT NULL,
    station_id VARCHAR(255) NOT NULL,
    status ENUM('OPEN', 'COMPLETED', 'CANCELLED') NOT NULL,
    item_count INT NOT NULL DEFAULT 0,
    running_total_cents BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (transaction_id),
    CONSTRAINT chk_transaction_counts CHECK (item_count >= 0 AND running_total_cents >= 0)
) ENGINE=InnoDB;

CREATE TABLE transaction_lines (
    transaction_id VARCHAR(64) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    unit_price_cents BIGINT NOT NULL,
    PRIMARY KEY (transaction_id, sku),
    CONSTRAINT fk_line_transaction FOREIGN KEY (transaction_id) REFERENCES checkout_transactions (transaction_id),
    CONSTRAINT fk_line_catalog FOREIGN KEY (sku) REFERENCES catalog_items (sku),
    CONSTRAINT chk_line_values CHECK (quantity > 0 AND unit_price_cents >= 0)
) ENGINE=InnoDB;

CREATE TABLE inventory_claims (
    transaction_id VARCHAR(64) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (transaction_id, sku),
    CONSTRAINT fk_claim_transaction FOREIGN KEY (transaction_id) REFERENCES checkout_transactions (transaction_id),
    CONSTRAINT fk_claim_inventory FOREIGN KEY (sku) REFERENCES inventory (sku),
    CONSTRAINT chk_claim_quantity CHECK (quantity > 0)
) ENGINE=InnoDB;

CREATE TABLE scan_events (
    sequence_number BIGINT NOT NULL,
    transaction_id VARCHAR(64) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    scanned_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (sequence_number),
    KEY idx_scan_events_sku_sequence (sku, sequence_number),
    CONSTRAINT fk_scan_transaction FOREIGN KEY (transaction_id) REFERENCES checkout_transactions (transaction_id),
    CONSTRAINT fk_scan_catalog FOREIGN KEY (sku) REFERENCES catalog_items (sku)
) ENGINE=InnoDB;

CREATE TABLE popularity_snapshots (
    window_end BIGINT NOT NULL,
    window_start BIGINT NOT NULL,
    window_size INT NOT NULL,
    slide_interval INT NOT NULL,
    computed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (window_end),
    CONSTRAINT chk_snapshot_bounds CHECK (window_start >= 0 AND window_end >= 0)
) ENGINE=InnoDB;

CREATE TABLE popularity_snapshot_items (
    window_end BIGINT NOT NULL,
    sku VARCHAR(64) NOT NULL,
    scan_count INT NOT NULL,
    rank_position INT NOT NULL,
    PRIMARY KEY (window_end, sku),
    KEY idx_snapshot_rank (window_end, rank_position),
    CONSTRAINT fk_snapshot_item_snapshot FOREIGN KEY (window_end) REFERENCES popularity_snapshots (window_end),
    CONSTRAINT fk_snapshot_item_catalog FOREIGN KEY (sku) REFERENCES catalog_items (sku),
    CONSTRAINT chk_snapshot_item_values CHECK (scan_count > 0 AND rank_position > 0)
) ENGINE=InnoDB;

CREATE TABLE inventory_movements (
    movement_id BIGINT NOT NULL AUTO_INCREMENT,
    sku VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(64) NULL,
    quantity_delta INT NOT NULL,
    stock_after INT NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (movement_id),
    KEY idx_inventory_movements_sku_time (sku, occurred_at),
    CONSTRAINT fk_movement_inventory FOREIGN KEY (sku) REFERENCES inventory (sku),
    CONSTRAINT fk_movement_transaction FOREIGN KEY (transaction_id) REFERENCES checkout_transactions (transaction_id)
) ENGINE=InnoDB;
