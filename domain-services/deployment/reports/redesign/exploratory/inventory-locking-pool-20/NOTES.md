# Exploratory run: Inventory locking fix with a Checkout pool of 20 (not a measured run)

`report-20261025-185118`: 200 stations × 180 s with `CHECKOUT_DB_POOL_SIZE=20`. All other pools
are 10. The code is the same as [`../inventory-locking/`](../inventory-locking/): the T119 commit
plus the tier 1 and tier 2 Inventory locking changes. This run is not part of `COMPARISON.md`.

| | Baseline stress median (pool 10) | Fix, pool 10 | **Fix, pool 20** | SC-009 threshold |
|---|---:|---:|---:|---:|
| Successful scans/s | 1,355.1 | 1,057 | **1,721.3** | ≥ 1,355.1 |
| Scan p95 | 295.5 ms | 290 ms | **174.9 ms** | ≤ 266.0 ms |
| Start p95 | 304.1 ms | 347 ms | **197.0 ms** | ≤ 304.1 ms |
| Complete p95 | 356.5 ms | 907 ms | **528.8 ms** | ≤ 356.5 ms |
| Transactions | 23,511 | 18,260 | 29,646 | |
| Errors | 0 | 0 | 0 | |
| Checkout pool: mean waiting / active / acquire | 181 / 9.9 / 55 ms | 178 / 9.5 / 69 ms | 168 / 19.9 / 37 ms | |
| checkout-db COMMIT mean | 2.9 ms (shared) | 3.8 ms | 5.2 ms | |
| Inventory row-lock wait per run | — | 34.8 s | 223.6 s | |
| Inventory pool: mean waiting / acquire | — | 0 / 0 ms | 0 / 0.4 ms | |
| Checkout server time, scan / start / complete | 118 / 121 / 154 ms | 123 / 142 / 512 ms | 71 / 79 / 306 ms | |
| Mean CPU: checkout-db / Checkout / gateway | MySQL 3.0 / 1.7 / 1.7 cores | 2.2 / 1.7 / 0.8 | 3.2 / 2.9 / 1.2 | |

Reconciliation passed: R-1 to R-6, 30,167 committed reservations equal to the finalized attempts,
no pending sale lines, and all 317,060 scans ingested.

## Reading

- **Against the SC-009 thresholds, three of the four targets would be met:** throughput (+27%),
  scan p95 and start p95. **Complete p95 would still be unmet**: 528.8 ms against at most 356.5 ms.
- **Checkout's pool is still the limit at 20.** All 20 connections are busy and about 168
  requests wait for one, with a 37 ms mean acquire time. More connections in flight also make each
  commit slower, 5.2 ms against 3.8 ms at 10, because checkout-db now uses 3.2 cores for redo
  flushing and commit work.
- **A completion still waits in that queue several times.** It runs freeze, prepare and finalize
  as separate Checkout transactions, each with a commit, plus two Inventory calls. That is about
  306 ms of Checkout server time, against 154 ms for the baseline's single transaction.
- **Inventory is no longer the bottleneck**, even with about 63% more completions: its pool never
  waits. Lock waiting rose to 224 s, against 1,505 s before the fix.

## Caveats before using this in the comparison

1. **Configuration difference (F9): now measured.** A baseline run with a Checkout pool of 20
   ([`../../../baseline/exploratory/pool-20/NOTES.md`](../../../baseline/exploratory/pool-20/NOTES.md))
   reached 1,428 scans/s, a scan p95 of 265.6 ms and a complete p95 of 409.0 ms.
   - The baseline becomes bound by row locks in `fulfill_checkout` (87 ms per call). The pool
     size alone gives the baseline +73 scans/s.
   - At equal pool size (20), the redesign with the fix is still ahead by +293 scans/s (+20.5%).
   - Its complete p95 is still worse: 528.8 against 409.0 ms.
2. **One run.** The measured runs (T119) repeat each profile three times. This is a single
   exploratory run on uncommitted code.
3. **Commit cost grows with concurrency.** If the pool is raised further, checkout-db flushing,
   now 3.2 cores and 5.2 ms per commit, is the next limit to watch.
