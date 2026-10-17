CREATE VIEW published_popularity AS SELECT i.window_end, i.sku, i.scan_count, i.rank_position FROM analytics.popularity_snapshot_items i;
