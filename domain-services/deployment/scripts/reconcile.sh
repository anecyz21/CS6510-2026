#!/usr/bin/env bash
# Settled-state reconciliation (004 data-model R-1..R-6, FR-019, SC-005). Reads each owner's
# read-only export over its operator port; it never connects to a database.
#
# Usage: reconcile.sh [--wait-settled SECONDS] [--out FILE]
#   --wait-settled  poll until no completion attempt is unresolved, no reservation is open, every
#                   committed sale is applied to stock, the scan outbox is drained and the analytics consumer has no lag (or the timeout
#                   passes), then evaluate
#   --out           also write the report to FILE
# Ports honour CHECKOUT_HOST_PORT, INVENTORY_HOST_PORT and ANALYTICS_HOST_PORT (environment or
# services.env). R-6 is SKIPPED, and the result FAIL, when Analytics cannot be reached.
# Requires bash, curl and jq. The last line is RECONCILIATION PASS (exit 0) or FAIL (exit 1).
set -euo pipefail
wait_settled=0
out=""
while [ $# -gt 0 ]; do
  case "$1" in
    --wait-settled) wait_settled="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
here="$(cd "$(dirname "$0")" && pwd)"
env_value() { sed -n "s/^$1=//p" "$here/../services.env" 2>/dev/null | tail -1 | tr -d '\r'; }
port() { local v="${!1:-$(env_value "$1")}"; printf '%s' "${v:-$2}"; }
checkout="http://127.0.0.1:$(port CHECKOUT_HOST_PORT 8082)/internal/v1/ops/reconciliation-export"
inventory="http://127.0.0.1:$(port INVENTORY_HOST_PORT 8083)/internal/v1/ops/reconciliation-export"
analytics="http://127.0.0.1:$(port ANALYTICS_HOST_PORT 8084)/internal/v1/ops/ingest-status"
catalog="http://127.0.0.1:$(port CATALOG_HOST_PORT 8081)/internal/v1/ops/publication"

# Exports go through files: Inventory's has one entry per SKU, too long for a command line on Windows.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fetch() { curl -sf --max-time 30 -o "$2" "$1" || { echo "cannot read $1" >&2; exit 1; }; }
# Analytics being away is reported by R-6 as SKIPPED (and the result FAIL), not as a script error.
fetch_analytics() { curl -sf --max-time 10 -o "$work/analytics.json" "$analytics" || echo null > "$work/analytics.json"; }
# Catalog publication progress (005 R-9); absent from the 004 build, so a missing endpoint is null.
fetch_catalog() { curl -sf --max-time 10 -o "$work/catalog.json" "$catalog" || echo null > "$work/catalog.json"; }

deadline=$(( $(date +%s) + wait_settled ))
while :; do
  fetch "$checkout" "$work/checkout.json"
  fetch "$inventory" "$work/inventory.json"
  fetch_analytics
  fetch_catalog
  settled=$(jq -n --slurpfile c "$work/checkout.json" --slurpfile i "$work/inventory.json" --slurpfile a "$work/analytics.json" \
    '($c[0].unresolvedAttempts == 0) and ($i[0].openReservations == 0)
     and (($i[0].pendingSaleLines // 0) == 0)
     and ($c[0].outboxPending == 0) and ($a[0] != null) and ($a[0].consumerLag == 0)
     and (($c[0].commandOutboxPending // 0) == 0) and (($i[0].replyOutboxPending // 0) == 0)' | tr -d '\r')
  [ "$settled" = true ] && break
  [ "$(date +%s)" -ge "$deadline" ] && break
  sleep 2
done

report=$(jq -rn --slurpfile cs "$work/checkout.json" --slurpfile is "$work/inventory.json" --slurpfile as "$work/analytics.json" --slurpfile ks "$work/catalog.json" '
  $cs[0] as $c | $is[0] as $i | $as[0] as $a | $ks[0] as $k |
  def line(name; ok; detail): "\(name) \(if ok then "PASS" else "FAIL" end)  \(detail)";
  ($i.positions | map(select(.reservedStock < 0 or .physicalStock < .reservedStock))) as $r1 |
  ($i.positions | map(. as $p | ($c.receiptQuantityBySku[$p.sku] // 0) as $sold
      | select(($p.initialStock - $p.physicalStock) != $sold)
      | {sku: $p.sku, initialMinusPhysical: ($p.initialStock - $p.physicalStock), receiptQuantity: $sold})) as $r4 |
  (($i.committedAttemptIds | sort) == ($c.finalizedAttemptIds | sort)) as $idsMatch |
  ([($i.saleQuantityBySku | keys[]), ($c.receiptQuantityBySku | keys[])] | unique
      | map(. as $s | select(($i.saleQuantityBySku[$s] // 0) != ($c.receiptQuantityBySku[$s] // 0)))) as $r5skus |
  [
    line("R-1 physical >= reserved >= 0"; ($r1 | length) == 0; "violations=\($r1 | length)"),
    line("R-2 no RESERVED reservation"; $i.openReservations == 0; "openReservations=\($i.openReservations)"),
    line("R-3 no unresolved attempt or freeze flag"; ($c.unresolvedAttempts == 0 and $c.activeAttemptFlags == 0);
         "unresolvedAttempts=\($c.unresolvedAttempts) activeAttemptFlags=\($c.activeAttemptFlags)"),
    line("R-4 initial - physical = receipt quantity"; (($r4 | length) == 0 and ($i.pendingSaleLines // 0) == 0);
         "skus=\($i.positions | length) violations=\($r4 | length) pendingSaleLines=\($i.pendingSaleLines // 0)"),
    line("R-5 committed reservations = finalized attempts"; ($idsMatch and ($r5skus | length) == 0);
         "committed=\($i.committedAttemptIds | length) finalized=\($c.finalizedAttemptIds | length) skuMismatches=\($r5skus | length)"),
    (if $a == null then "R-6 accepted scans = ingested scans SKIPPED (analytics unreachable)"
     else line("R-6 accepted scans = ingested scans";
               ($c.outboxPending == 0 and $a.consumerLag == 0 and $c.outboxTotal == $a.ingestedCount);
               "outboxTotal=\($c.outboxTotal) ingestedCount=\($a.ingestedCount) outboxPending=\($c.outboxPending) consumerLag=\($a.consumerLag)")
     end),
    # 005 additions (data-model "Reconciliation additions"). Fields missing from a 004 build are skipped, not failed.
    (if $c.commandOutboxPending == null then empty
     else line("R-7 every outbox drained"; ($c.commandOutboxPending == 0 and $i.replyOutboxPending == 0 and $c.outboxPending == 0);
               "commandOutboxPending=\($c.commandOutboxPending) replyOutboxPending=\($i.replyOutboxPending) scanOutboxPending=\($c.outboxPending)") end),
    (if $c.deadLetterRecords == null then empty
     else line("R-8 no dead-lettered records"; ($c.deadLetterRecords == 0 and $i.deadLetterRecords == 0 and (($a.deadLetterRecords // 0) == 0));
               "checkout=\($c.deadLetterRecords) inventory=\($i.deadLetterRecords) analytics=\($a.deadLetterRecords // "n/a")") end),
    (if $k == null then empty
     else line("R-9 catalog published and every copy at its version";
               ($k.publishedVersion == $k.catalogVersion and $c.projectionCatalogVersion == $k.catalogVersion
                and $i.projectionCatalogVersion == $k.catalogVersion and ($a.projectionCatalogVersion // $k.catalogVersion) == $k.catalogVersion);
               "catalogVersion=\($k.catalogVersion) published=\($k.publishedVersion) checkout=\($c.projectionCatalogVersion) inventory=\($i.projectionCatalogVersion) analytics=\($a.projectionCatalogVersion // "n/a")") end)
  ] + ($r1 | map("  R-1 \(.sku) physical=\(.physicalStock) reserved=\(.reservedStock)"))
    + ($r4 | map("  R-4 \(.sku) initial-physical=\(.initialMinusPhysical) receipts=\(.receiptQuantity)"))
    + ($r5skus | map("  R-5 \(.) sale=\($i.saleQuantityBySku[.] // 0) receipts=\($c.receiptQuantityBySku[.] // 0)"))
  | .[]' | tr -d '\r')

if grep -qE ' (FAIL|SKIPPED) ' <<< "$report"; then result="RECONCILIATION FAIL"; status=1; else result="RECONCILIATION PASS"; status=0; fi
{
  echo "reconciliation at $(date -u +%Y-%m-%dT%H:%M:%SZ) (exports: $checkout, $inventory, $analytics)"
  echo "$report"
  echo "$result"
} | if [ -n "$out" ]; then tee "$out"; else cat; fi
exit "$status"
