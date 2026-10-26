-- Analytics ingestion and publication (004 research R5, data-model "Analytics").
-- The old tables fed by polling Checkout's store are replaced; existing snapshots were built from
-- that poller (finding F1) and are discarded.
DROP TABLE processed_scans;
DROP TABLE analytics_events;

-- One row per accepted scan. event_id is the deduplication key; sequence_number is the canonical
-- order, assigned on first ingest and never changed.
CREATE TABLE ingested_scans (
  event_id CHAR(36) PRIMARY KEY,
  sequence_number BIGINT NOT NULL,
  sku VARCHAR(64) NOT NULL,
  accepted_at TIMESTAMP(6) NOT NULL,
  ingested_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_ingested_sequence UNIQUE (sequence_number)
);

-- Locked by every ingest batch, which serializes sequence assignment (fixes finding F2).
CREATE TABLE ingest_state (
  id TINYINT PRIMARY KEY,
  last_sequence BIGINT NOT NULL,
  CONSTRAINT chk_ingest_state_single_row CHECK (id = 1)
);
INSERT INTO ingest_state (id, last_sequence) VALUES (1, 0);

DELETE FROM popularity_snapshot_items;
DELETE FROM popularity_snapshots;
ALTER TABLE popularity_snapshot_items ADD COLUMN name VARCHAR(255) NOT NULL;

-- Readers follow this pointer, which moves only after a snapshot is complete (fixes finding F3).
-- NULL means nothing has been published yet: the empty result.
CREATE TABLE published_snapshot (
  id TINYINT PRIMARY KEY,
  current_window_end BIGINT NULL,
  CONSTRAINT chk_published_snapshot_single_row CHECK (id = 1)
);
INSERT INTO published_snapshot (id, current_window_end) VALUES (1, NULL);

-- Unparseable or unsupported events are recorded, acknowledged and never counted.
CREATE TABLE ingest_rejections (
  kafka_offset BIGINT PRIMARY KEY,
  reason VARCHAR(255) NOT NULL,
  raw_value MEDIUMTEXT,
  rejected_at TIMESTAMP(6) NOT NULL
);

-- Name-only catalog projection (research R7); catalog_version NULL means never loaded.
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
