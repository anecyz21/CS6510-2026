SELECT p.sku, p.initial_stock, p.available_stock, COALESCE(SUM(r.quantity), 0) AS completed_quantity
FROM inventory.inventory_positions p
LEFT JOIN checkout.receipt_lines r ON r.sku = p.sku
GROUP BY p.sku, p.initial_stock, p.available_stock
HAVING p.available_stock < 0 OR p.initial_stock - p.available_stock <> COALESCE(SUM(r.quantity), 0);
