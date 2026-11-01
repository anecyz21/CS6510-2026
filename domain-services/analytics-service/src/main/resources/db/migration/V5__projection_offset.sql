-- The name projection is built from Catalog's product topic (005 research R3): topic_offset is the last
-- applied record, written with the rows; NULL means replay from the beginning. The 004 staleness
-- probe is gone; catalog_version now holds the highest applied version.
ALTER TABLE projection_state
  ADD COLUMN topic_offset BIGINT NULL,
  DROP COLUMN last_verified_at;
