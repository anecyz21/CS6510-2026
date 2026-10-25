-- Measured-run stock for the BASELINE schema (tag baseline/service-based-v1), research R14.
--
-- Run with the inventory owner credentials after Flyway has migrated the inventory schema, e.g.
--   docker compose -p <project> -f <baseline compose file> exec -T mysql \
--     mysql -uinventory_service -pinventory_service inventory < measured-stock-baseline.sql
--
-- The baseline schema has inventory.inventory_positions(initial_stock, available_stock,
-- reserved_stock). 1,000,000 units per SKU covers more than 15x the projected top-SKU demand
-- (about 12.2% of up to ~600k scans). Shortage behavior is tested separately, never in measured
-- runs. The statement is idempotent: re-running it yields the same state.
UPDATE inventory_positions
SET initial_stock = 1000000,
    available_stock = 1000000,
    reserved_stock = 0;
