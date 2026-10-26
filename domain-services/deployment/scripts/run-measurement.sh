#!/usr/bin/env bash
# Runs measured workloads against one topology (research R14, quickstart V10).
#
# Usage:
#   run-measurement.sh --topology baseline|redesign --compose-file FILE --stock-sql FILE \
#       --profile normal|stress --report-dir DIR [--runs 3] [--project NAME] [--env-file FILE]
#
# For each run: reset every volume, rebuild and start the stack, wait for readiness, apply the
# stock seed with the inventory owner's credentials, warm up for 30 s (report discarded), run the
# unchanged load client while capture-observations.sh samples, then reconcile. Outputs in DIR:
#   report-<ts>.json (load client), observations-<ts>.json, reconciliation-<ts>.txt
# where <ts> is the load client's own report timestamp, so the three files pair by name.
#
# Uses only documented load-client flags. If JAVA_HOME is set, its java is used for Maven and the
# load client (the code targets Java 21).
set -euo pipefail

topology="" compose_file="" stock_sql="" profile="" report_dir="" runs=3 project="" env_file=""
while [ $# -gt 0 ]; do
  case "$1" in
    --topology) topology="$2"; shift 2 ;;
    --compose-file) compose_file="$2"; shift 2 ;;
    --stock-sql) stock_sql="$2"; shift 2 ;;
    --profile) profile="$2"; shift 2 ;;
    --report-dir) report_dir="$2"; shift 2 ;;
    --runs) runs="$2"; shift 2 ;;
    --project) project="$2"; shift 2 ;;
    --env-file) env_file="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
for v in topology compose_file stock_sql profile report_dir; do
  [ -n "${!v}" ] || { echo "missing --${v//_/-}" >&2; exit 2; }
done
case "$profile" in
  normal) stations=10; duration=60 ;;
  stress) stations=200; duration=180 ;;
  *) echo "--profile must be normal or stress" >&2; exit 2 ;;
esac
[ "$topology" = baseline ] || [ "$topology" = redesign ] || { echo "--topology must be baseline or redesign" >&2; exit 2; }
project="${project:-perf-$topology}"

[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(git -C "$here" rev-parse --show-toplevel)"                       # load client lives here
tree="$(git -C "$(dirname "$compose_file")" rev-parse --show-toplevel)"  # code under test
compose_file="$(cd "$(dirname "$compose_file")" && pwd)/$(basename "$compose_file")"
stock_sql="$(cd "$(dirname "$stock_sql")" && pwd)/$(basename "$stock_sql")"
mkdir -p "$report_dir"; report_dir="$(cd "$report_dir" && pwd)"
[ -n "$env_file" ] && env_file="$(cd "$(dirname "$env_file")" && pwd)/$(basename "$env_file")"

native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
compose() {
  if [ -n "$env_file" ]; then docker compose -p "$project" -f "$compose_file" --env-file "$env_file" "$@"
  else docker compose -p "$project" -f "$compose_file" "$@"; fi
}
env_value() { sed -n "s/^$1=//p" "$env_file" | tail -1 | tr -d '\r'; }
log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }

# Owner credentials and reconciliation per topology.
if [ "$topology" = baseline ]; then
  inv_service=mysql inv_user=inventory_service inv_password=inventory_service inv_db=inventory
else
  [ -n "$env_file" ] || { echo "redesign requires --env-file (services.env)" >&2; exit 2; }
  inv_service=inventory-db inv_user=inventory_service inv_password="$(env_value INVENTORY_DB_PASSWORD)" inv_db=inventory
fi

wait_ready() {
  local deadline=$(( $(date +%s) + 300 )) total healthy
  while :; do
    total=$(compose ps --format '{{.Service}}' | wc -l)
    healthy=$(compose ps --format '{{.Health}}' | grep -c '^healthy$' || true)
    if [ "$total" -gt 0 ] && [ "$healthy" -eq "$total" ] && curl -sf http://localhost:8080/actuator/health >/dev/null; then
      return 0
    fi
    [ "$(date +%s)" -lt "$deadline" ] || { log "stack not ready after 300 s"; compose ps; return 1; }
    sleep 3
  done
}

post_start() {
  # The baseline needs its three documented cross-schema object grants once Flyway has created
  # the objects (baseline deployment README). Its script runs `docker compose` without -p, so the
  # project name is passed through COMPOSE_PROJECT_NAME.
  if [ "$topology" = baseline ]; then
    COMPOSE_PROJECT_NAME="$project" "$tree/domain-services/deployment/scripts/grant-cross-domain-interfaces.sh" >/dev/null 2>&1
  fi
}

smoke_check() { # one start -> scan -> complete through the gateway; aborts on any non-2xx
  local tx code
  tx=$(curl -sf -XPOST http://localhost:8080/transactions -H 'content-type: application/json' \
         -d '{"stationId":"measurement-smoke"}' | sed -n 's/.*"transactionId":"\([^"]*\)".*/\1/p')
  [ -n "$tx" ] || { log "smoke check: start failed"; return 1; }
  code=$(curl -s -o /dev/null -w '%{http_code}' -XPOST "http://localhost:8080/transactions/$tx/items" \
           -H 'content-type: application/json' -d '{"sku":"SKU-000001"}')
  [ "$code" = 200 ] || { log "smoke check: scan returned $code"; return 1; }
  code=$(curl -s -o /dev/null -w '%{http_code}' -XPOST "http://localhost:8080/transactions/$tx/complete")
  [ "$code" = 200 ] || { log "smoke check: complete returned $code"; return 1; }
}

reconcile() { # file
  if [ "$topology" = baseline ]; then
    # The baseline check lives in the baseline tree (tag baseline/service-based-v1); the redesign uses reconcile.sh.
    local rows
    rows=$(compose exec -T mysql mysql -uroot -proot -N -B < "$tree/domain-services/integration-tests/scripts/reconcile-inventory.sql" 2>/dev/null | tr -d '\r')
    {
      echo "baseline reconciliation: domain-services/integration-tests/scripts/reconcile-inventory.sql at tag baseline/service-based-v1 (root, shared instance)"
      echo "rule: available_stock >= 0 and initial_stock - available_stock = completed receipt-line quantity, per SKU"
      if [ -z "$rows" ]; then echo "violating SKUs: none"; echo "RECONCILIATION PASS"
      else echo "violating SKUs (sku, initial, available, completed):"; echo "$rows"; echo "RECONCILIATION FAIL"; fi
    } > "$1"
  elif [ -x "$here/reconcile.sh" ]; then
    "$here/reconcile.sh" --wait-settled 60 > "$1" 2>&1 || true
  else
    echo "reconcile.sh not found; RECONCILIATION NOT RUN" > "$1"
  fi
}

log "building $tree with $(java -version 2>&1 | head -1)"
mvn -q -f "$tree/domain-services/pom.xml" -DskipTests package

for run in $(seq 1 "$runs"); do
  log "[$topology/$profile run $run/$runs] resetting stack ($project)"
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  compose up -d --build >/dev/null 2>&1
  wait_ready
  post_start

  log "applying stock seed $(basename "$stock_sql")"
  compose exec -T "$inv_service" mysql -u"$inv_user" -p"$inv_password" "$inv_db" < "$stock_sql" 2>/dev/null
  smoke_check || { log "aborting: the stack is not serving checkout correctly"; exit 1; }

  log "warm-up: 10 stations x 30 s (report discarded)"
  warm=$(mktemp -d)
  "$repo/load-client/run.sh" --stations=10 --duration=30 --reportDir="$(native "$warm")" > "$warm/client.log" 2>&1
  if grep -q '"errorCount" *: *[1-9]' "$warm"/report-*.json; then
    log "aborting: warm-up had operation errors (see $warm)"; exit 1
  fi
  rm -rf "$warm"

  stop="$(mktemp -u)"
  obs_tmp=$(mktemp -d)
  "$here/capture-observations.sh" --out "$obs_tmp" --topology "$topology" --compose-file "$compose_file" \
    --project "$project" ${env_file:+--env-file "$env_file"} --interval 5 --duration $(( duration + 120 )) \
    --stop-file "$stop" --stock-seed "$(basename "$stock_sql")" --profile "$profile" > "$obs_tmp/path" &
  capture_pid=$!

  log "measured run: $stations stations x $duration s"
  before=$(ls "$report_dir"/report-*.json 2>/dev/null | sort || true)
  set +e
  "$repo/load-client/run.sh" --stations="$stations" --duration="$duration" --reportDir="$(native "$report_dir")" \
    > "$obs_tmp/client.log" 2>&1
  client_status=$?
  set -e
  touch "$stop"
  wait "$capture_pid"
  rm -f "$stop"

  report=$(comm -13 <(printf '%s\n' "$before") <(ls "$report_dir"/report-*.json | sort) | tail -1)
  [ -n "$report" ] || { log "load client wrote no report (exit $client_status); see $obs_tmp/client.log"; exit 1; }
  ts="$(basename "$report" .json)"; ts="${ts#report-}"
  mv "$(cat "$obs_tmp/path")" "$report_dir/observations-$ts.json"
  cp "$obs_tmp/client.log" "$report_dir/client-$ts.log"
  rm -rf "$obs_tmp"

  reconcile "$report_dir/reconciliation-$ts.txt"
  log "done: report-$ts.json (client exit $client_status), $(tail -1 "$report_dir/reconciliation-$ts.txt")"
done

compose down -v --remove-orphans >/dev/null 2>&1 || true
log "all $runs $profile runs complete"
