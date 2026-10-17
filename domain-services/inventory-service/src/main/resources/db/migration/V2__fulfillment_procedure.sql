DELIMITER //
CREATE PROCEDURE fulfill_checkout(IN requested_sku VARCHAR(64), IN requested_quantity INT, IN request_transaction VARCHAR(64))
BEGIN
  DECLARE available INT;
  SELECT available_stock INTO available FROM inventory_positions WHERE sku = requested_sku FOR UPDATE;
  SET available = LEAST(available, requested_quantity);
  IF available > 0 THEN
    UPDATE inventory_positions SET available_stock = available_stock - available WHERE sku = requested_sku;
    INSERT INTO inventory_movements(sku, transaction_id, quantity_delta, stock_after) SELECT requested_sku, request_transaction, -available, available_stock FROM inventory_positions WHERE sku = requested_sku;
  END IF;
  SELECT available AS fulfilled_quantity, requested_quantity - available AS unavailable_quantity;
END //
DELIMITER ;
