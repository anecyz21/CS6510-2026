CREATE DATABASE IF NOT EXISTS catalog;
CREATE DATABASE IF NOT EXISTS checkout;
CREATE DATABASE IF NOT EXISTS inventory;
CREATE DATABASE IF NOT EXISTS analytics;
CREATE USER IF NOT EXISTS 'catalog_service'@'%' IDENTIFIED BY 'catalog_service';
CREATE USER IF NOT EXISTS 'checkout_service'@'%' IDENTIFIED BY 'checkout_service';
CREATE USER IF NOT EXISTS 'inventory_service'@'%' IDENTIFIED BY 'inventory_service';
CREATE USER IF NOT EXISTS 'analytics_service'@'%' IDENTIFIED BY 'analytics_service';
GRANT ALL PRIVILEGES ON catalog.* TO 'catalog_service'@'%';
GRANT ALL PRIVILEGES ON checkout.* TO 'checkout_service'@'%';
GRANT ALL PRIVILEGES ON inventory.* TO 'inventory_service'@'%';
GRANT ALL PRIVILEGES ON analytics.* TO 'analytics_service'@'%';

-- Cross-schema object grants are applied after owner Flyway migrations create the interfaces.
-- See deployment/scripts/grant-cross-domain-interfaces.sh; schema-wide grants are never used.
FLUSH PRIVILEGES;
