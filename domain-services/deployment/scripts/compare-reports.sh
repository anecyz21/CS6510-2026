#!/usr/bin/env bash
# Compares the baseline and redesign measured runs (research R14, FR-031, SC-009) and writes
# domain-services/deployment/reports/COMPARISON.md.
#
# Usage: compare-reports.sh [--reports DIR] [--out FILE]
#
# Reads every report-*.json directly in reports/baseline/ and reports/redesign/ (subfolders such as
# redesign/exploratory/ are not measured runs), with the observations-<ts>.json and
# reconciliation-<ts>.txt of the same timestamp. A run is "stress" when config.stations == 200,
# otherwise "normal". No run and no failed request is ever dropped: every run appears in the
# per-run table with its error count, and every run of a profile counts toward its median.
#
# A hand-written "## Analysis" section (task T120) at the end of an existing COMPARISON.md is kept
# when the file is regenerated. Requires bash and jq.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
reports="$here/../reports"
out=""
while [ $# -gt 0 ]; do
  case "$1" in
    --reports) reports="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
out="${out:-$reports/COMPARISON.md}"
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# One compact JSON object per run, with the few observation fields the comparison needs.
collect() { # topology
  local dir="$reports/$1" report ts obs rec recon
  for report in "$dir"/report-*.json; do
    [ -e "$report" ] || continue
    ts="$(basename "$report" .json)"; ts="${ts#report-}"
    obs="$dir/observations-$ts.json"; rec="$dir/reconciliation-$ts.txt"
    recon="MISSING"
    [ -f "$rec" ] && recon="$(grep -E 'RECONCILIATION (PASS|FAIL)' "$rec" | tail -1 | tr -d '\r')"
    [ -n "$recon" ] || recon="NO RESULT"
    if [ -f "$obs" ]; then
      jq -c '{commit, describe, stockSeed, timeouts,
              host: .host.dockerInfo, hostOs: .host.hostOs, others: .host.otherRunningContainers,
              mysqls: (.mysql.start | keys),
              pools: (.metrics.start | with_entries(.value |= (.["hikaricp.connections.max"].measurements[0].value? // null))
                                      | with_entries(select(.value != null)))}' "$obs" > "$work/obs.json"
    else
      echo null > "$work/obs.json"
    fi
    jq -c --arg topology "$1" --arg ts "$ts" --arg recon "$recon" --slurpfile obs "$work/obs.json" '
      (.operations | map({key: .operation, value: .}) | from_entries) as $ops |
      {topology: $topology, ts: $ts,
       profile: (if .config.stations == 200 then "stress" else "normal" end),
       stations: .config.stations, duration: .config.durationSecondsRequested, wall: .wallClockSeconds,
       transactions: .totalTransactions,
       scans: $ops.SCAN_ITEM.successCount,
       throughput: ($ops.SCAN_ITEM.successCount / .wallClockSeconds),
       scanMean: $ops.SCAN_ITEM.meanMs, scanP95: $ops.SCAN_ITEM.p95Ms, scanP99: $ops.SCAN_ITEM.p99Ms,
       startP95: $ops.START_TRANSACTION.p95Ms, completeP95: $ops.COMPLETE_TRANSACTION.p95Ms,
       errors: ([.operations[].errorCount] | add),
       errorsByOp: ([.operations[] | select(.errorCount > 0) | "\(.operation) \(.errorCount)"] | join(", ")),
       recon: $recon, obs: $obs[0]}' "$report" | tr -d '\r'
  done
}
{ collect baseline; collect redesign; } > "$work/runs.jsonl"
[ -s "$work/runs.jsonl" ] || { echo "no reports found under $reports/{baseline,redesign}" >&2; exit 1; }

jq -rs '
  def r1: if . == null then "n/a" else ((. * 10 | round) / 10 | tostring) end;
  def median: sort | if length == 0 then null elif length % 2 == 1 then .[length / 2 | floor]
                     else (.[length / 2 - 1] + .[length / 2]) / 2 end;
  def med($t; $p; f): [.[] | select(.topology == $t and .profile == $p) | f] | median;
  def count($t; $p): [.[] | select(.topology == $t and .profile == $p)] | length;
  def verdict(ok): if ok == null then "NOT EVALUATED (missing runs)" elif ok then "**MET**" else "**UNMET**" end;
  def uniq(f): [.[] | f] | unique | map(if type == "string" then . else tojson end) | join("; ");
  . as $runs |
  {b: med("baseline"; "stress"; .scanP95), r: med("redesign"; "stress"; .scanP95)} as $scan |
  {b: med("baseline"; "stress"; .throughput), r: med("redesign"; "stress"; .throughput)} as $tput |
  {b: med("baseline"; "stress"; .startP95), r: med("redesign"; "stress"; .startP95)} as $start |
  {b: med("baseline"; "stress"; .completeP95), r: med("redesign"; "stress"; .completeP95)} as $complete |
  def both(m): if m.b == null or m.r == null then null else true end;
  [
    "## Runs",
    "",
    "Every measured run, failures included. Scans/s is successful scans divided by the report wall-clock seconds.",
    "",
    "| Topology | Run | Profile | Transactions | Successful scans | Scans/s | Scan mean / p95 / p99 (ms) | Start p95 (ms) | Complete p95 (ms) | Errors | Reconciliation |",
    "|---|---|---|---:|---:|---:|---|---:|---:|---|---|",
    ($runs | sort_by(.topology, .profile, .ts)[] |
      "| \(.topology) | `report-\(.ts)` | \(.profile) | \(.transactions) | \(.scans) | \(.throughput | r1) | \(.scanMean | r1) / \(.scanP95 | r1) / \(.scanP99 | r1) | \(.startP95 | r1) | \(.completeP95 | r1) | \(.errors)\(if .errors > 0 then " (" + .errorsByOp + ")" else "" end) | \(.recon) |"),
    "",
    "## Medians",
    "",
    "| Metric | Baseline normal | Redesign normal | Baseline stress | Redesign stress |",
    "|---|---:|---:|---:|---:|",
    "| Runs | \(count("baseline"; "normal")) | \(count("redesign"; "normal")) | \(count("baseline"; "stress")) | \(count("redesign"; "stress")) |",
    "| Scan p95 (ms) | \(med("baseline"; "normal"; .scanP95) | r1) | \(med("redesign"; "normal"; .scanP95) | r1) | \($scan.b | r1) | \($scan.r | r1) |",
    "| Successful scans/s | \(med("baseline"; "normal"; .throughput) | r1) | \(med("redesign"; "normal"; .throughput) | r1) | \($tput.b | r1) | \($tput.r | r1) |",
    "| Start p95 (ms) | \(med("baseline"; "normal"; .startP95) | r1) | \(med("redesign"; "normal"; .startP95) | r1) | \($start.b | r1) | \($start.r | r1) |",
    "| Complete p95 (ms) | \(med("baseline"; "normal"; .completeP95) | r1) | \(med("redesign"; "normal"; .completeP95) | r1) | \($complete.b | r1) | \($complete.r | r1) |",
    "| Errors (all runs) | \([$runs[] | select(.topology == "baseline" and .profile == "normal") | .errors] | add // 0) | \([$runs[] | select(.topology == "redesign" and .profile == "normal") | .errors] | add // 0) | \([$runs[] | select(.topology == "baseline" and .profile == "stress") | .errors] | add // 0) | \([$runs[] | select(.topology == "redesign" and .profile == "stress") | .errors] | add // 0) |",
    "",
    "## SC-009 verdicts (stress medians)",
    "",
    "| Target | Baseline | Redesign | Threshold | Verdict |",
    "|---|---:|---:|---:|---|",
    "| Scan p95: redesign ≤ 0.90 × baseline | \($scan.b | r1) ms | \($scan.r | r1) ms | ≤ \(if $scan.b == null then null else $scan.b * 0.9 end | r1) ms | \(verdict(if both($scan) == null then null else $scan.r <= 0.9 * $scan.b end)) |",
    "| Successful scan throughput: redesign ≥ baseline | \($tput.b | r1)/s | \($tput.r | r1)/s | ≥ \($tput.b | r1)/s | \(verdict(if both($tput) == null then null else $tput.r >= $tput.b end)) |",
    "| Start p95: redesign ≤ baseline | \($start.b | r1) ms | \($start.r | r1) ms | ≤ \($start.b | r1) ms | \(verdict(if both($start) == null then null else $start.r <= $start.b end)) |",
    "| Complete p95: redesign ≤ baseline | \($complete.b | r1) ms | \($complete.r | r1) ms | ≤ \($complete.b | r1) ms | \(verdict(if both($complete) == null then null else $complete.r <= $complete.b end)) |",
    "",
    "## Declared differences",
    "",
    "Taken from the observation files of the runs above (one row per value seen).",
    "",
    "| Item | Baseline | Redesign |",
    "|---|---|---|",
    ([$runs[] | select(.topology == "baseline")] as $b | [$runs[] | select(.topology == "redesign")] as $r |
      "| Host (Docker) | \($b | uniq(.obs.host | "\(.ncpu) CPUs, \(.memTotalBytes / 1073741824 | r1) GiB, Docker \(.serverVersion), kernel \(.kernel)")) | \($r | uniq(.obs.host | "\(.ncpu) CPUs, \(.memTotalBytes / 1073741824 | r1) GiB, Docker \(.serverVersion), kernel \(.kernel)")) |",
      "| Host OS | \($b | uniq(.obs.hostOs)) | \($r | uniq(.obs.hostOs)) |",
      "| Other running containers | \($b | uniq(.obs.others | if . == "" then "none" else . end)) | \($r | uniq(.obs.others | if . == "" then "none" else . end)) |",
      "| Topology | one MySQL 8.4 instance (\($b | uniq(.obs.mysqls | join(", ")))) shared by four schemas; no broker | \($r | uniq(.obs.mysqls | length | tostring)) private MySQL 8.4 instances (\($r | uniq(.obs.mysqls | join(", ")))) plus Kafka 3.8.1 (KRaft, one broker) |",
      "| Hikari pool sizes | \($b | uniq(.obs.pools | to_entries | map("\(.key) \(.value | floor)") | join(", "))) | \($r | uniq(.obs.pools | to_entries | map("\(.key) \(.value | floor)") | join(", "))) |",
      "| Stock seed | \($b | uniq(.obs.stockSeed)) | \($r | uniq(.obs.stockSeed)) |",
      "| Timeouts | \($b | uniq(.obs.timeouts | "load client \(.loadClientRequestTimeoutSeconds) s; \(.note)")) | \($r | uniq(.obs.timeouts | "load client \(.loadClientRequestTimeoutSeconds) s; \(.note)")) |",
      "| Commit | \($b | uniq("\(.obs.commit) (\(.obs.describe))")) | \($r | uniq("\(.obs.commit) (\(.obs.describe))")) |",
      "| Workload | \($b | uniq("\(.stations) stations × \(.duration) s")) | \($r | uniq("\(.stations) stations × \(.duration) s")) |")
  ] | .[]' "$work/runs.jsonl" | tr -d '\r' > "$work/generated.md"

analysis=""
if [ -f "$out" ] && grep -q '^## Analysis' "$out"; then
  analysis="$(sed -n '/^## Analysis/,$p' "$out" | tr -d '\r')"
fi

{
  cat <<'EOF'
# Baseline versus redesign comparison

Generated by `domain-services/deployment/scripts/compare-reports.sh` from the measured runs in
[`baseline/`](baseline/) and [`redesign/`](redesign/) (research R14, FR-031, SC-009). The SC-009
targets compare stress medians only; normal-load latency is dominated by the gateway POST stall
(F12 below) in both topologies.

EOF
  cat "$work/generated.md"
  cat <<'EOF'

### Known baseline functional defects

From `specs/004-domain-services/research.md`, "Findings from the current implementation". The
*Effect* column says how each defect bears on the measured runs.

| Finding | Baseline behavior | Redesign behavior | Effect on measured runs |
|---|---|---|---|
| **F1** | Analytics re-reads the same first 500 `checkout.accepted_scan_outbox` rows every `analytics.poll-ms` (1 s) and never advances. It does almost no analytics work but sends about 500 no-op `INSERT IGNORE` statements per second to the shared instance. | Every scan is ingested through the outbox, the relay and Kafka. | The analytics load on the database differs in both directions, so analytics cost is not comparable. |
| **F2** | Sequence numbers are assigned with `MAX()+1` outside a lock. | Sequence numbers come from a locked `ingest_state` row. | Negligible on measured runs, because F1 stops ingestion after 500 scans. |
| **F3** | Snapshots are deleted and reinserted for the same `window_end`. | New snapshot rows plus a pointer flip. | Popular-items results can be partial in the baseline. The latency effect is negligible. |
| **F4** | Low-stock and popular items return the SKU as `name`. | Names come from projected catalog names. | None on latency; the read-view correctness differs. |
| **F5** | The gateway turns every upstream 4xx into a 500. | Upstream 4xx pass through with typed `ApiError` codes. | None with adequate stock, where no 4xx is expected. Any baseline 500s must be examined for masked 4xx before they are compared. |
| **F6** | Completion is one Checkout transaction that calls `inventory.fulfill_checkout`, with one cumulative receipt row per transaction. | The completion saga with per-attempt receipts. | The architectural change under test; complete-latency differences are attributable to it. |
| **F9** | Checkout uses a Hikari pool of 10 with up to 8 scan deadlock retries. | The pool size chosen in T118 (see `redesign/POOL-SIZING.md` and the pool row above). | A configuration difference, quantified from the `hikaricp.*` and `checkout.db.retries` observations. |
| **F12** | At low concurrency about 40 ms is added to every POST with a body (start and scan) that reaches the gateway from a Java client on the Windows host, but not to complete. Baseline normal runs: gateway server time about 47.5 ms per scan against 3.3 ms in Checkout; under stress the gap is about 2.3 ms. Cause: Docker Desktop's port forwarding for the all-interfaces `8080:8080` binding (`127.0.0.1:8080:8080` reduced the same request from 48.5 ms to 6.2 ms; inside the Docker network the gateway adds 1–2 ms). See `baseline/OBSERVATIONS.md` and `redesign/GATEWAY-HOP.md`. | Port 8080 is published the same way, so the same stall applies. | Dominates normal-load start and scan latency in both topologies; it is not an architecture difference. *Corrected in T120:* in the baseline the gap disappears under stress, but in the redesign the scan gap (gateway minus Checkout server time, about 42 ms) persists under stress, so it inflates the redesign's stress scan p95. It works against the redesign and does not change the scan verdict. |
| F7 | One MySQL instance shared by all schemas. | Private instances. | Covered by the topology row above. |
| F8 | Inadequate stock in the historical runs. | — | Removed for both topologies by the 1,000,000-unit provisioning. |
| F10, F11 | — | — | No runtime effect. |
EOF
  if [ -n "$analysis" ]; then printf '\n%s\n' "$analysis"; fi
} > "$out"
echo "wrote $out"
