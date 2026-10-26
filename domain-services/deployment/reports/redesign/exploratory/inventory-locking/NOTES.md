# Exploratory run: Inventory locking fix (not a measured run)

`report-20261025-184509`: 200 stations × 180 s, all pools 10, the same procedure as the measured
runs. Code: the T119 commit plus two Inventory changes.

- **Tier 1:** reserve takes no up-front `FOR UPDATE` read. Each line is one conditional update in
  descending SKU order, and the grant comes back through `LAST_INSERT_ID`. The lines are inserted
  in one batch.
- **Tier 2:** commit only queues its sales in `sale_queue`. `SaleApplier` applies them every 100 ms,
  with one position update per SKU per batch.

This is not part of `COMPARISON.md`. These figures are compared with the run before the fix
([`../pool-10/`](../pool-10/)) and with the baseline stress medians.

| | Baseline (median) | Redesign before fix (pool-10) | Redesign with fix |
|---|---:|---:|---:|
| Successful scans/s | 1,355.1 | 916 | **1,057** |
| Scan p95 | 295.5 ms | 70 ms | 290 ms |
| Start p95 | 304.1 ms | 53 ms | 347 ms |
| Complete p95 | 356.5 ms | 1,969 ms | **907 ms** |
| Errors | 0 | 3 (complete 503) | **0** |
| Inventory row-lock wait per run | — | 1,505 s | **34.8 s** |
| Inventory pool: mean waiting / acquire | — | 135 / 761 ms | **0 / 0 ms** |
| Checkout pool: mean waiting / acquire | 181 / 55 ms | 5 / 3.4 ms | 178 / 69 ms |
| Checkout server time, scan / complete | 118 / 154 ms | 10.7 / 1,703 ms | 123 / 512 ms |
| checkout-db COMMIT mean | 2.9 ms (shared) | 3.7 ms | 3.8 ms |
| Mean CPU: checkout-db / Checkout | MySQL 3.0 / 1.7 cores | 1.7 / 1.6 cores | 2.2 / 1.7 cores |

Reconciliation passed: R-1 to R-6, 18,790 committed reservations equal to the finalized attempts,
no pending sale lines, and all 197,137 scans ingested.

## Reading

- **The Inventory bottleneck is gone.** Lock waiting fell by 98%, Inventory's pool is idle, and
  completion's p95 more than halved, with no 503s.
- **The bottleneck moved back to Checkout's pool of 10, as in the baseline.** About 178 requests
  wait for a connection, with a 69 ms mean acquire time.
  - Completions now finish quickly, so stations send scans and starts at a higher rate, and all
    three operations queue for the same 10 connections.
  - A completion takes several Checkout transactions: freeze, prepare and finalize, each with a
    commit of about 3.8 ms. It therefore waits in that queue several times, which is most of its
    512 ms.
- **What this means for T118.** `POOL-SIZING.md` chose 10 because Checkout's pool was not the
  bottleneck before this fix. With the fix it is, so the pool size question is open again. A larger
  Checkout pool would reintroduce a configuration difference from the baseline (F9), which
  `COMPARISON.md` would have to declare and attribute.
