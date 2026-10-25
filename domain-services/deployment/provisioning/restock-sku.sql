-- Controlled restocking of one SKU for tests (US1-4) on the REDESIGN schema.
-- Run with the inventory owner's credentials against inventory-db, prefixing the two parameters:
--   { echo "SET @sku = 'SKU-000001'; SET @qty = 1;"; cat domain-services/deployment/provisioning/restock-sku.sql; } |
--     docker compose -f domain-services/deployment/docker-compose.yml --env-file domain-services/deployment/services.env \
--       exec -T inventory-db sh -c 'mysql -uinventory_service -p"$MYSQL_PASSWORD" inventory'
--
-- Adds @qty to both initial_stock and physical_stock, so reconciliation (initial - physical =
-- purchased units) still holds, and records a PROVISION movement without an attempt id.
-- No HTTP provisioning endpoint exists (contracts/inventory-internal-api.md).
START TRANSACTION;
UPDATE inventory_positions
SET initial_stock = initial_stock + @qty,
    physical_stock = physical_stock + @qty
WHERE sku = @sku;
INSERT INTO inventory_movements (sku, attempt_id, movement_type, quantity_delta, physical_after, occurred_at)
SELECT sku, NULL, 'PROVISION', @qty, physical_stock, CURRENT_TIMESTAMP(6)
FROM inventory_positions WHERE sku = @sku;
COMMIT;
