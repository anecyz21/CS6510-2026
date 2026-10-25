# Baseline observations: service-based system before migration

This folder is the pre-migration baseline required by constitution V and FR-030
([research R14](../../../../specs/004-domain-services/research.md#r14-baseline-and-performance-method-fr-030-fr-031-sc-009)).
It is the "before" side of the SC-009 comparison.

| | |
|---|---|
| Code under test | Tag `baseline/service-based-v1` (commit `f2875ad`): commit `8f40554` plus an instrumentation-only change (actuator metrics, explicit Hikari pool size 10, a scan-retry counter) |
| Topology | Original Docker Compose stack: one MySQL 8.4 instance shared by four schemas, plus five Spring Boot containers (catalog, checkout, inventory, analytics, gateway) |
| Stock | `provisioning/measured-stock-baseline.sql`: 1,000,000 units per SKU before every run |
| Procedure | `scripts/run-measurement.sh`: reset all volumes, rebuild, apply the three documented cross-schema grants, seed stock, one start/scan/complete smoke check, 30 s warm-up (discarded), measured run, reconciliation |
| Workloads | Unchanged load client. Normal: `--stations=10 --duration=60`. Stress: `--stations=200 --duration=180`. Three runs of each |
| Host | Intel Core i7-13700K (16 cores, 24 threads), 31.7 GiB RAM, Windows 10 Pro 10.0.19045. Docker Desktop 29.1.3 on WSL2 with 24 CPUs and 15.5 GiB available to containers. No per-container CPU or memory limits |
| Host load | No other containers ran during any measured run (`otherRunningContainers` is empty in every observations file) |
| Date | 2026-10-25 (UTC timestamps 07:20–07:38) |

## Run results

Every run had **zero operation errors** and passed reconciliation: for every SKU,
`initial_stock − available_stock` equals the completed receipt-line quantity, and no stock is
negative.

| Run | Profile | Transactions | Successful scans | Scans/s | Scan mean / p95 / p99 (ms) | Start p95 (ms) | Complete p95 (ms) | Errors | Reconciliation |
|---|---|---:|---:|---:|---|---:|---:|---:|---|
| `report-20261025-002058` | normal | 1,056 | 11,289 | 186.5 | 48.1 / 52.9 / 56.3 | 51.3 | 14.4 | 0 | PASS |
| `report-20261025-002307` | normal | 1,062 | 11,291 | 186.6 | 48.2 / 52.8 / 55.8 | 51.2 | 15.5 | 0 | PASS |
| `report-20261025-002517` | normal | 1,071 | 11,265 | 185.5 | 48.3 / 52.9 / 55.6 | 51.4 | 15.0 | 0 | PASS |
| `report-20261025-002931` | stress | 23,302 | 244,141 | 1,343.9 | 121.3 / 295.5 / 434.1 | 310.6 | 359.4 | 0 | PASS |
| `report-20261025-003338` | stress | 23,933 | 249,986 | 1,377.5 | 118.6 / 290.8 / 425.6 | 300.1 | 348.1 | 0 | PASS |
| `report-20261025-003751` | stress | 23,511 | 245,995 | 1,355.1 | 120.5 / 296.0 / 433.9 | 304.1 | 356.5 | 0 | PASS |

Each report pairs by timestamp with `observations-<ts>.json`, `reconciliation-<ts>.txt` and
`client-<ts>.log`. Scans/s counts successful scans divided by the report's wall-clock seconds.

### Medians used for SC-009

| Metric | Normal (median of 3) | Stress (median of 3) |
|---|---:|---:|
| Scan p95 | 52.9 ms | **295.5 ms** |
| Successful scan throughput | 186.5 scans/s | **1,355.1 scans/s** |
| Start p95 | 51.3 ms | **304.1 ms** |
| Complete p95 | 15.0 ms | **356.5 ms** |

SC-009 targets for the redesign, derived from the stress medians:
- stress scan p95 at most **266.0 ms** (0.90 × 295.5);
- successful scan throughput at least **1,355.1 scans/s**;
- start p95 at most **304.1 ms**;
- complete p95 at most **356.5 ms**.

The runs are tightly grouped. The stress scan p95 spread is 5.3 ms (1.8%), and throughput varies
by 2.5%.

## Where the time goes

The figures below come from the observations files. Each is end-of-run minus start-of-run for
cumulative counters, the mean over 5-second samples for gauges, and the measurement window only for
MySQL statement digests.

### Normal load: the gateway hop dominates scan latency

| Per request, mean (ms) | Start | Scan | Complete |
|---|---:|---:|---:|
| Load client (end to end) | 46.7 | 48.2 | 9.1 |
| Gateway server time | 39.0 | 47.5 | 8.5 |
| Checkout server time | 2.5 | 3.3 | 7.9 |
| **Gap: gateway minus Checkout** | **36.5** | **44.2** | **0.6** |

- About 44 of the 48 ms a scan takes at normal load is spent between the gateway receiving the
  request and Checkout receiving it. Start shows the same gap, but complete does not. Start and scan
  are the two forwarded POSTs that carry a request body; complete has no body.
- The gateway forwards with `RestClient.create(url)` and no tuning (`HttpCheckoutClient`). A fixed
  ~40 ms stall that appears only on requests with a body, only at low concurrency, is the usual
  signature of Nagle's algorithm interacting with delayed TCP acknowledgements. This mechanism is
  **inferred, not confirmed**, for example by a packet capture.
- The stall disappears under stress: the gateway adds only about 2.3 ms per scan there (see the
  next section). Busy connections are acknowledged promptly, which fits the inference.
- Checkout's database pool is idle at normal load. Pending connection requests were always 0,
  mean pool acquire time was below 0.001 ms, and the mean number of active connections was below 1.
- **Consequence for the redesign:** normal-load scan latency mostly measures this hop, not the
  architecture. The redesign gateway also forwards with the JDK `HttpClient` (task T038). Any
  normal-load change must be checked against this gap before it is attributed to anything else.
  It doesn't affect the SC-009 targets, which are stress-only.

### Stress load: Checkout's connection pool is the bottleneck

| Per request, mean (ms) | Start | Scan | Complete |
|---|---:|---:|---:|
| Gateway server time | 123.4 | 119.8 | 151.1 |
| Checkout server time | 120.6 | 117.5 | 151.1 |
| Gap | 2.8 | 2.3 | 0.0 |

The pool figures come from Checkout's Hikari metrics:

| Checkout pool (10 connections) | Run 002931 | Run 003338 | Run 003751 |
|---|---:|---:|---:|
| Mean threads waiting for a connection | 181.4 | 175.9 | 185.3 |
| Max threads waiting | 188 | 189 | 188 |
| Mean active connections | 9.95 | 9.55 | 9.86 |
| Mean connection-acquire time | 55.2 ms | 53.9 ms | 54.8 ms |
| Max connection-acquire time | 1,121 ms | 944 ms | 1,065 ms |
| Acquire timeouts | 0 | 0 | 0 |

- All 10 connections are busy almost all the time, and about 180 of the 200 stations' requests
  are queued for one.
- Waiting for a connection accounts for about 54 ms of Checkout's ~118 ms per scan. Most of the
  rest is time spent queued in Tomcat and executing while other requests hold connections.
- Throughput is consistent with a pool-bound system. Checkout serves about 1,614 requests/s
  (scans plus starts plus completes), so 10 connections imply a mean hold time of about 6.2 ms per
  request (Little's law).
- CPU is not the limit. Over the stress runs, MySQL averaged about 3 cores (300%, peak 378%),
  Checkout about 1.7 cores and the gateway about 1.7 cores, out of 24 available to Docker.
- The Inventory and Catalog pools never had a waiting request. Inventory's procedure runs on
  Checkout's connection, so Inventory's own pool is unused by completion.

### Stress load: where database time goes

These are the top statements by total execution time in one stress run (`observations-20261025-003751.json`).
The other two stress runs differ by less than 0.5 percentage points.

| Share | Calls | Mean (ms) | Statement |
|---:|---:|---:|---|
| 55.3% | 286,524 | 2.90 | `COMMIT` (checkout) |
| 28.6% | 23,073 | 18.60 | `CALL inventory.fulfill_checkout(...)` (completion) |
| 1.9% | 263,627 | 0.11 | basket totals `SELECT SUM(...)` |
| 1.9% | 240,799 | 0.12 | `SELECT … FROM catalog.active_items WHERE sku = ?` (cross-schema price read) |
| 1.6% | 573,551 | 0.04 | `SET autocommit = ?` |
| 1.5% | 240,851 | 0.10 | `UPDATE basket_lines SET quantity = quantity + 1 …` |
| 1.4% | 213,947 | 0.10 | `INSERT INTO basket_lines …` |
| 1.4% | 240,846 | 0.09 | transaction-open check `SELECT COUNT(*) …` |

- **Commits** are the largest cost. Every scan commits its own transaction, and a commit waits
  for the redo-log flush (about 2.9 ms each on this Docker Desktop volume).
- **Completion's stored procedure** averages 18.6 ms because it locks inventory rows for every SKU
  in the basket. The load client's Zipf SKU choice makes a few SKUs hot.
- **Row-lock contention**:
  - about 21,000 row-lock waits per stress run, totalling about 406 seconds of waiting summed
    across sessions;
  - versus about 220 waits and 0.5 s at normal load.

  The procedure runs on a Checkout connection, so this lock waiting also holds Checkout pool
  connections and feeds the pool bottleneck above.

## Bottleneck hypotheses (research R14)

| Hypothesis | Verdict | Evidence |
|---|---|---|
| Checkout pool wait with 200 stations and 10 connections | **Confirmed**: the dominant stress bottleneck | About 180 threads waiting on average, all 10 connections active, about 54 ms mean acquire time (about 46% of Checkout scan time), and throughput consistent with 10 connections × about 6 ms hold. At normal load the pool is idle. |
| Shared-instance contention from Analytics polling and Inventory locks | **Split** | *Analytics polling: rejected.* Analytics statements are 0.3% of MySQL statement time under stress and 4.4% at normal load. These are about 31,000 no-op `INSERT IGNORE INTO processed_scans` per normal run, about 500 per second, from defect F1. *Inventory locks: confirmed, but as row-lock contention, not instance-level resource contention.* `fulfill_checkout` is 28.6% of statement time with about 21,000 row-lock waits per stress run, while MySQL used about 3 of 24 cores. |
| Two queries per scan plus a cross-schema read | **Confirmed as a contributor** | Each scan makes about 8 database round trips and takes **two** pool connections in turn. In `CheckoutService.acceptScan`, one transaction turns autocommit off, checks the transaction is open, reads the cross-schema price, updates or inserts the basket line, inserts the scan event, commits and restores autocommit. The controller then reads the basket totals as a separate autocommit query, which acquires a connection again. Each query is cheap (about 0.1 ms server time), but together with the 2.9 ms commit and the second acquisition they set the about 6 ms connection hold time that the 10-connection pool multiplies into queueing. |
| Deadlock retries | **Rejected as a material bottleneck** | 76–84 deadlocks and 77–85 scan retries per stress run, out of about 245,000 scans (0.03%). Every retry succeeded, so there were 0 errors. At normal load: 47–61 per run. |

## Additional observations

- **Gateway POST stall (new finding).** At low concurrency the gateway adds about 40 ms to every
  forwarded POST that carries a body. This is not in research's list of findings. It should be
  added to the *Known baseline functional defects* table in `COMPARISON.md` (task T117) and
  checked in the redesign gateway (task T038).
- **Comparison with the historical 2026-10-16 stress run.** That run had a scan p95 of 260.8 ms at
  1,554 scans/s. With adequate stock, every completion now purchases its whole basket and locks
  more inventory rows, and this baseline is slower: 295.5 ms at 1,355 scans/s. That run's
  shortage-driven partial completions made its numbers look better; see
  [`../historical/README.md`](../historical/README.md).
- **Popular-items results in these reports are not valid.** Defect F1 means Analytics ingested only
  the first 500 scans, and defect F4 means the reports show SKUs as names. They are reported
  unchanged and are not used in any comparison.
- **Instrumentation.** Adding actuator replaced each service's hand-written `/actuator/health`
  response with Spring's own. It answers the same path with `UP` when the database is reachable.
  No other behavior changed.

## Reproducing

From the repository root, with Docker running, nothing else running in Docker, and `JAVA_HOME`
pointing at JDK 21:

```bash
git worktree add ../cs6510-baseline baseline/service-based-v1
```

```bash
domain-services/deployment/scripts/run-measurement.sh --topology baseline --compose-file ../cs6510-baseline/domain-services/deployment/docker-compose.yml --stock-sql domain-services/deployment/provisioning/measured-stock-baseline.sql --profile normal --runs 3 --report-dir domain-services/deployment/reports/baseline
```

```bash
domain-services/deployment/scripts/run-measurement.sh --topology baseline --compose-file ../cs6510-baseline/domain-services/deployment/docker-compose.yml --stock-sql domain-services/deployment/provisioning/measured-stock-baseline.sql --profile stress --runs 3 --report-dir domain-services/deployment/reports/baseline
```
