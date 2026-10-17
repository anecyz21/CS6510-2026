#!/usr/bin/env sh
# Run after owner migrations complete. These are the only cross-schema grants.
set -eu
deployment_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
docker compose -f "$deployment_dir/docker-compose.yml" exec -T mysql mysql -uroot -proot -e "
GRANT SELECT ON catalog.active_items TO 'checkout_service'@'%';
GRANT EXECUTE ON PROCEDURE inventory.fulfill_checkout TO 'checkout_service'@'%';
GRANT SELECT ON checkout.accepted_scan_outbox TO 'analytics_service'@'%';
FLUSH PRIVILEGES;"
