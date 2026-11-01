#!/usr/bin/env bash
# Captures bottleneck observations for one measured run (research R14, FR-030) into
# <out>/observations-<UTC timestamp>.json. Needs only bash, docker and git: actuator and MySQL
# already return JSON, so their output is embedded verbatim and no JSON tool is required.
#
# Usage:
#   capture-observations.sh --out DIR --topology baseline|redesign|pre-event-driven|event-driven --compose-file FILE \
#       [--project NAME] [--env-file FILE] [--interval 5] [--duration SECONDS] \
#       [--stop-file PATH] [--stock-seed NAME] [--profile normal|stress]
#
# Sampling runs every --interval seconds until --stop-file exists or --duration elapses,
# whichever comes first. Cumulative counters are snapshotted at the start and the end, so a run's
# figures are end minus start. MySQL statement digests are reset at the start, so they cover the
# measurement window only.
set -euo pipefail

out="" topology="" compose_file="" project="" env_file="" interval=5 duration=600
stop_file="" stock_seed="" profile=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) out="$2"; shift 2 ;;
    --topology) topology="$2"; shift 2 ;;
    --compose-file) compose_file="$2"; shift 2 ;;
    --project) project="$2"; shift 2 ;;
    --env-file) env_file="$2"; shift 2 ;;
    --interval) interval="$2"; shift 2 ;;
    --duration) duration="$2"; shift 2 ;;
    --stop-file) stop_file="$2"; shift 2 ;;
    --stock-seed) stock_seed="$2"; shift 2 ;;
    --profile) profile="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$out" ] && [ -n "$topology" ] && [ -n "$compose_file" ] || {
  echo "usage: $0 --out DIR --topology baseline|redesign|pre-event-driven|event-driven --compose-file FILE [...]" >&2; exit 2; }
project="${project:-perf-$topology}"
mkdir -p "$out"

compose() {
  if [ -n "$env_file" ]; then docker compose -p "$project" -f "$compose_file" --env-file "$env_file" "$@"
  else docker compose -p "$project" -f "$compose_file" "$@"; fi
}
env_value() { [ -n "$env_file" ] && sed -n "s/^$1=//p" "$env_file" | tail -1 | tr -d '\r'; }
json_str() { printf '"%s"' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')"; }

# --- topology description -------------------------------------------------------------------
# service:port pairs, the routes whose server timers are captured, and mysql service:password.
case "$topology" in
  baseline)
    services="catalog:8081 checkout:8082 inventory:8083 analytics:8084 gateway:8080"
    checkout_routes="/transactions /transactions/{id}/items /transactions/{id}/complete /transactions/{id}"
    gateway_routes="/transactions /transactions/{transactionId}/items /transactions/{transactionId}/complete /items /inventory/low-stock /analytics/popular-items"
    mysqls="mysql:root"
    ;;
  redesign|pre-event-driven|event-driven)   # the 005 topologies share 004's private-store layout
    services="catalog:8081 checkout:8082 inventory:8083 analytics:8084 gateway:8080"
    checkout_routes="/transactions /transactions/{id}/items /transactions/{id}/complete /transactions/{id}"
    gateway_routes="/transactions /transactions/{transactionId}/items /transactions/{transactionId}/complete /items /inventory/low-stock /analytics/popular-items"
    mysqls="catalog-db:$(env_value CATALOG_DB_ROOT_PASSWORD) checkout-db:$(env_value CHECKOUT_DB_ROOT_PASSWORD) inventory-db:$(env_value INVENTORY_DB_ROOT_PASSWORD) analytics-db:$(env_value ANALYTICS_DB_ROOT_PASSWORD)"
    ;;
  *) echo "--topology must be baseline, redesign, pre-event-driven or event-driven" >&2; exit 2 ;;
esac

uri_query() { # route -> encoded metrics query for its uri tag
  printf 'http.server.requests?tag=uri:%s' "$(printf '%s' "$1" | sed 's/{/%7B/g; s/}/%7D/g')"
}

# Fetches several actuator metrics with ONE docker exec. Arguments: service port key=path...
# Prints a JSON object {key: <actuator JSON or null>, ...}.
service_metrics() {
  local svc="$1" port="$2"; shift 2
  compose exec -T "$svc" sh -c '
    port="$1"; shift; sep=""; printf "{"
    for kv in "$@"; do
      key="${kv%%=*}"; path="${kv#*=}"
      body=$(wget -qO- "http://localhost:$port/actuator/metrics/$path" 2>/dev/null) || body=null
      [ -n "$body" ] || body=null
      printf "%s\"%s\":%s" "$sep" "$key" "$body"; sep=","
    done
    printf "}"' sh "$port" "$@" 2>/dev/null || printf 'null'
}

snapshot_metrics() { # full cumulative snapshot of every service
  local sep="" entry svc port args route
  printf '{'
  for entry in $services; do
    svc="${entry%%:*}"; port="${entry#*:}"
    args=("hikaricp.connections.max=hikaricp.connections.max"
          "hikaricp.connections.acquire=hikaricp.connections.acquire"
          "hikaricp.connections.usage=hikaricp.connections.usage"
          "hikaricp.connections.timeout=hikaricp.connections.timeout"
          "process.cpu.usage=process.cpu.usage"
          "jvm.threads.live=jvm.threads.live"
          "http.server.requests=http.server.requests"
          "http.client.requests=http.client.requests")
    case "$svc" in
      checkout) args+=("checkout.db.retries=checkout.db.retries")
                # 005: saga round trip (command written to terminal reply applied), outbox and reply handling.
                args+=("checkout.saga.roundtrip=checkout.saga.roundtrip"
                       "checkout.saga.roundtrip.p50=checkout.saga.roundtrip.percentile?tag=phi:0.5"
                       "checkout.saga.roundtrip.p95=checkout.saga.roundtrip.percentile?tag=phi:0.95"
                       "checkout.saga.roundtrip.p99=checkout.saga.roundtrip.percentile?tag=phi:0.99"
                       "checkout.saga.reply.ignored=checkout.saga.reply.ignored"
                       "checkout.command_outbox.pending=checkout.command_outbox.pending"
                       "checkout.recovery.resends=checkout.recovery.resends"
                       "messaging.dlt.published=messaging.dlt.published")
                for route in $checkout_routes; do args+=("http.server.requests $route=$(uri_query "$route")"); done ;;
      inventory) args+=("inventory.reply_outbox.pending=inventory.reply_outbox.pending" "messaging.dlt.published=messaging.dlt.published") ;;
      analytics) args+=("messaging.dlt.published=messaging.dlt.published") ;;
      gateway)  for route in $gateway_routes; do args+=("http.server.requests $route=$(uri_query "$route")"); done ;;
    esac
    printf '%s"%s":%s' "$sep" "$svc" "$(service_metrics "$svc" "$port" "${args[@]}")"; sep=","
  done
  printf '}'
}

sample_pools() { # point-in-time pool gauges for the domain services
  local sep="" entry svc port
  printf '{'
  for entry in $services; do
    svc="${entry%%:*}"; port="${entry#*:}"
    [ "$svc" = gateway ] && continue
    printf '%s"%s":%s' "$sep" "$svc" "$(service_metrics "$svc" "$port" \
      "pending=hikaricp.connections.pending" "active=hikaricp.connections.active")"; sep=","
  done
  printf '}'
}

mysql_json() { # service password sql -> raw JSON value printed by MySQL
  compose exec -T "$1" mysql -uroot -p"$2" -N -B -r -e "$3" 2>/dev/null | tr -d '\r' | tail -1
}

snapshot_mysql() { # phase(start|end)
  local sep="" entry svc pw innodb status digests
  printf '{'
  for entry in $mysqls; do
    svc="${entry%%:*}"; pw="${entry#*:}"
    innodb=$(mysql_json "$svc" "$pw" "SELECT JSON_ARRAYAGG(JSON_OBJECT('name',NAME,'count',COUNT,'status',STATUS)) FROM information_schema.INNODB_METRICS WHERE NAME IN ('lock_deadlocks','lock_timeouts','lock_row_lock_time','lock_row_lock_time_max','lock_row_lock_waits')")
    status=$(mysql_json "$svc" "$pw" "SELECT JSON_OBJECTAGG(VARIABLE_NAME, VARIABLE_VALUE) FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_time_max','Threads_connected','Threads_running','Questions','Com_select','Com_insert','Com_update','Com_delete','Com_call_procedure','Innodb_buffer_pool_read_requests','Innodb_buffer_pool_reads','Innodb_data_fsyncs','Innodb_os_log_written')")
    if [ "$1" = start ]; then
      compose exec -T "$svc" mysql -uroot -p"$pw" -e "TRUNCATE TABLE performance_schema.events_statements_summary_by_digest" >/dev/null 2>&1 || true
      digests=null
    else
      digests=$(mysql_json "$svc" "$pw" "SELECT JSON_ARRAYAGG(j) FROM (SELECT JSON_OBJECT('rank',ROW_NUMBER() OVER (ORDER BY SUM_TIMER_WAIT DESC),'schema',SCHEMA_NAME,'digestText',LEFT(DIGEST_TEXT,400),'count',COUNT_STAR,'sumTimerWaitMs',ROUND(SUM_TIMER_WAIT/1e9,3),'avgTimerWaitMs',ROUND(AVG_TIMER_WAIT/1e9,3),'sumLockTimeMs',ROUND(SUM_LOCK_TIME/1e9,3),'sumRowsExamined',SUM_ROWS_EXAMINED,'sumErrors',SUM_ERRORS) AS j FROM performance_schema.events_statements_summary_by_digest ORDER BY SUM_TIMER_WAIT DESC LIMIT 20) AS top")
    fi
    printf '%s"%s":{"innodbMetrics":%s,"globalStatus":%s,"topDigests":%s}' "$sep" "$svc" \
      "${innodb:-null}" "${status:-null}" "${digests:-null}"; sep=","
  done
  printf '}'
}

docker_stats() {
  local ids
  ids=$(compose ps -q | tr '\n' ' ')
  [ -n "$ids" ] || { printf '[]'; return; }
  # shellcheck disable=SC2086
  printf '[%s]' "$(docker stats --no-stream --format '{{json .}}' $ids | paste -sd, -)"
}

now() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }

# --- capture ----------------------------------------------------------------------------------
stamp=$(date -u +%Y%m%d-%H%M%S)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
tree=$(git -C "$(dirname "$compose_file")" rev-parse --show-toplevel)
started=$(now)

ours=$(compose ps --format '{{.Name}}' | sort)
foreign=$(docker ps --format '{{.Names}}' | sort | comm -23 - <(printf '%s\n' "$ours") | paste -sd, -)

{
  printf '"host":{"dockerInfo":%s,' "$(docker info --format '{"ncpu":{{.NCPU}},"memTotalBytes":{{.MemTotal}},"operatingSystem":{{json .OperatingSystem}},"kernel":{{json .KernelVersion}},"serverVersion":{{json .ServerVersion}}}')"
  printf '"hostOs":%s,' "$(json_str "$(uname -a)")"
  printf '"otherRunningContainers":%s},' "$(json_str "$foreign")"
} > "$work/host"

snapshot_metrics > "$work/metrics-start"
snapshot_mysql start > "$work/mysql-start"

deadline=$(( $(date +%s) + duration ))
: > "$work/samples"
sep=""
while :; do
  { printf '%s{"at":"%s","pools":%s,"dockerStats":%s}' "$sep" "$(now)" "$(sample_pools)" "$(docker_stats)"; } >> "$work/samples"
  sep=","
  [ -n "$stop_file" ] && [ -e "$stop_file" ] && break
  [ "$(date +%s)" -ge "$deadline" ] && break
  sleep "$interval"
done

ended=$(now)
# Consumer lag per group at the end of the run (005 research R11); empty on a build without Kafka consumers.
# Rows are joined with " | " so the text stays one valid JSON string after json_str.
kafka_groups=$(compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups 2>/dev/null \
  | tr -d '\r' | tr -s ' ' | grep -v '^ *$' | paste -sd'|' - | sed 's/|/ | /g' || true)
snapshot_metrics > "$work/metrics-end"
snapshot_mysql end > "$work/mysql-end"

file="$out/observations-$stamp.json"
{
  printf '{"schema":"observations/v1","topology":%s,"composeProject":%s,' "$(json_str "$topology")" "$(json_str "$project")"
  printf '"composeFile":%s,"commit":%s,' "$(json_str "$compose_file")" "$(json_str "$(git -C "$tree" rev-parse HEAD)")"
  printf '"describe":%s,"profile":%s,"stockSeed":%s,' "$(json_str "$(git -C "$tree" describe --tags --always 2>/dev/null || true)")" "$(json_str "$profile")" "$(json_str "$stock_seed")"
  printf '"timeouts":{"loadClientRequestTimeoutSeconds":10,"note":%s},' \
    "$(json_str "baseline: internal HTTP and JDBC timeouts are framework defaults (none configured); redesign: see research R9")"
  printf '"startedAt":"%s","endedAt":"%s","intervalSeconds":%s,' "$started" "$ended" "$interval"
  cat "$work/host"
  printf '"metrics":{"start":%s,"end":%s},' "$(cat "$work/metrics-start")" "$(cat "$work/metrics-end")"
  printf '"mysql":{"start":%s,"end":%s},' "$(cat "$work/mysql-start")" "$(cat "$work/mysql-end")"
  printf '"kafkaConsumerGroups":%s,' "$(json_str "$kafka_groups")"
  printf '"samples":[%s]}\n' "$(cat "$work/samples")"
} > "$file"
echo "$file"
