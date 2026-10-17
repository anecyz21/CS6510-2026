CREATE TABLE processed_scans (scan_id BIGINT PRIMARY KEY, processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6));
CREATE TABLE analytics_events (sequence_number BIGINT PRIMARY KEY, scan_id BIGINT NOT NULL UNIQUE, sku VARCHAR(64) NOT NULL, accepted_at TIMESTAMP(6) NOT NULL);
CREATE TABLE popularity_snapshots (window_end BIGINT PRIMARY KEY, window_start BIGINT NOT NULL, computed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6));
CREATE TABLE popularity_snapshot_items (window_end BIGINT NOT NULL, sku VARCHAR(64) NOT NULL, scan_count BIGINT NOT NULL, rank_position INT NOT NULL, PRIMARY KEY(window_end, sku));
