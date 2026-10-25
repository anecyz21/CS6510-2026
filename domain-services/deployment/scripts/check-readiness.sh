#!/usr/bin/env bash
# Waits for every private database, Kafka and application to be ready (004 research R17).
# Domain services report readiness for their OWN database and migrations only, so each one can
# be ready while other domains are stopped. Exits non-zero after --timeout seconds (default 180).
# Honours CATALOG_HOST_PORT, CHECKOUT_HOST_PORT, INVENTORY_HOST_PORT and ANALYTICS_HOST_PORT from
# the environment or services.env.
set -euo pipefail
timeout=180
[ "${1:-}" = "--timeout" ] && timeout="$2"
here="$(cd "$(dirname "$0")" && pwd)"
compose() { docker compose -f "$here/../docker-compose.yml" --env-file "$here/../services.env" "$@"; }
env_value() { sed -n "s/^$1=//p" "$here/../services.env" 2>/dev/null | tail -1 | tr -d '\r'; }
port() { local v="${!1:-$(env_value "$1")}"; printf '%s' "${v:-$2}"; }

deadline=$(( $(date +%s) + timeout ))
check() { # name command...
  local name="$1"; shift
  until "$@" >/dev/null 2>&1; do
    if [ "$(date +%s)" -ge "$deadline" ]; then printf '%-14s NOT READY\n' "$name"; return 1; fi
    sleep 2
  done
  printf '%-14s ready\n' "$name"
}
healthy() { [ "$(compose ps --format '{{.Health}}' "$1" 2>/dev/null)" = healthy ]; }

status=0
for db in catalog-db checkout-db inventory-db analytics-db kafka; do check "$db" healthy "$db" || status=1; done
for entry in "catalog:$(port CATALOG_HOST_PORT 8081)" "checkout:$(port CHECKOUT_HOST_PORT 8082)" \
             "inventory:$(port INVENTORY_HOST_PORT 8083)" "analytics:$(port ANALYTICS_HOST_PORT 8084)"; do
  check "${entry%%:*}" curl -sf "http://127.0.0.1:${entry#*:}/actuator/health/readiness" || status=1
done
check gateway curl -sf http://localhost:8080/actuator/health || status=1
[ "$status" -eq 0 ] && echo "all services ready" || { echo "some services are not ready" >&2; exit 1; }
