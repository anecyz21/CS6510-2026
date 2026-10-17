DROP PROCEDURE fulfill_checkout;
DELIMITER //
CREATE PROCEDURE fulfill_checkout(IN request_transaction VARCHAR(64), IN requested JSON)
BEGIN
  DECLARE locked_sku VARCHAR(64);
  DECLARE exhausted BOOL DEFAULT FALSE;
  -- Opening this cursor locks Inventory rows in SKU order without emitting a JDBC result set.
  DECLARE lock_cursor CURSOR FOR
    SELECT p.sku FROM inventory_positions p JOIN fulfillment_result r ON r.sku = p.sku ORDER BY p.sku FOR UPDATE;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET exhausted = TRUE;
  DROP TEMPORARY TABLE IF EXISTS fulfillment_result;
  CREATE TEMPORARY TABLE fulfillment_result (sku VARCHAR(64) PRIMARY KEY, requested_quantity INT NOT NULL, fulfilled_quantity INT NOT NULL, unavailable_quantity INT NOT NULL);
  -- Lock only Inventory-owned rows in deterministic SKU order before calculating fulfillment.
  INSERT INTO fulfillment_result(sku, requested_quantity, fulfilled_quantity, unavailable_quantity)
    SELECT r.sku, r.quantity, 0, r.quantity
    FROM JSON_TABLE(requested, '$[*]' COLUMNS (sku VARCHAR(64) PATH '$.sku', quantity INT PATH '$.quantity')) r
    ORDER BY r.sku;
  OPEN lock_cursor;
  lock_rows: LOOP
    FETCH lock_cursor INTO locked_sku;
    IF exhausted THEN LEAVE lock_rows; END IF;
  END LOOP;
  CLOSE lock_cursor;
  UPDATE fulfillment_result r JOIN inventory_positions p ON p.sku = r.sku
    SET r.fulfilled_quantity = LEAST(p.available_stock, r.requested_quantity),
        r.unavailable_quantity = r.requested_quantity - LEAST(p.available_stock, r.requested_quantity);
  INSERT INTO inventory_movements(sku, transaction_id, quantity_delta, stock_after)
    SELECT r.sku, request_transaction, -r.fulfilled_quantity, p.available_stock - r.fulfilled_quantity FROM fulfillment_result r JOIN inventory_positions p ON p.sku = r.sku WHERE r.fulfilled_quantity > 0;
  UPDATE inventory_positions p JOIN fulfillment_result r ON r.sku = p.sku SET p.available_stock = p.available_stock - r.fulfilled_quantity;
  SELECT sku, fulfilled_quantity, unavailable_quantity FROM fulfillment_result ORDER BY sku;
  DROP TEMPORARY TABLE fulfillment_result;
END //
DELIMITER ;
