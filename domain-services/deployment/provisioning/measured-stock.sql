-- Measured-run stock for the REDESIGN schema (Inventory's private store), research R14.
--
-- Run with the inventory owner credentials after reset, once Flyway has migrated Inventory, e.g.
--   docker compose -p <project> -f domain-services/deployment/docker-compose.yml \
--     --env-file domain-services/deployment/services.env exec -T inventory-db \
--     mysql -uinventory_service -p<INVENTORY_DB_PASSWORD> inventory < measured-stock.sql
--
-- Brings every SKU to 1,000,000 physical units, the same level as measured-stock-baseline.sql.
-- Unlike the baseline seed, it keeps reconciliation R-4 (initial - physical = sold) true: each SKU
-- gets a PROVISION movement for the added quantity, and initial_stock grows by the same amount.
-- The movements are inserted first so they read the pre-update stock. Running it again on
-- provisioned stock adds nothing (delta 0) apart from zero-quantity movements.
START TRANSACTION;

INSERT INTO inventory_movements (sku, attempt_id, movement_type, quantity_delta, physical_after, occurred_at)
SELECT sku, NULL, 'PROVISION', 1000000 - physical_stock, 1000000, CURRENT_TIMESTAMP(6)
FROM inventory_positions;

UPDATE inventory_positions
SET initial_stock = initial_stock + (1000000 - physical_stock),
    physical_stock = 1000000;

COMMIT;
