-- Private store (004 research R2): Checkout no longer calls into this database, and the low-stock
-- view is no longer granted to anyone.
DROP PROCEDURE IF EXISTS fulfill_checkout;
DROP VIEW low_stock;

-- available_stock becomes physical_stock: it changes only when a reservation commits or stock is
-- provisioned, never when stock is merely reserved. The four unnamed V1 CHECK constraints are
-- generated as inventory_positions_chk_1..4 and must go before the column can be renamed.
ALTER TABLE inventory_positions
  DROP CHECK inventory_positions_chk_1,
  DROP CHECK inventory_positions_chk_2,
  DROP CHECK inventory_positions_chk_3,
  DROP CHECK inventory_positions_chk_4;
ALTER TABLE inventory_positions RENAME COLUMN available_stock TO physical_stock;
ALTER TABLE inventory_positions
  ADD CONSTRAINT chk_initial_nonneg CHECK (initial_stock >= 0),
  ADD CONSTRAINT chk_physical_ge_reserved CHECK (physical_stock >= reserved_stock),
  ADD CONSTRAINT chk_reserved_nonneg CHECK (reserved_stock >= 0);
