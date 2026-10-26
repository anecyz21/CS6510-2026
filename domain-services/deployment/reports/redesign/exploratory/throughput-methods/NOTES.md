# Exploratory runs: merged completion steps, binlog off, pool sizes (not measured runs)

Every run here is 200 stations × 180 s, with the same procedure and stock seed as the measured
runs: reset, seed, smoke check, 30 s warm-up, measured run, then reconciliation R-1 to R-6. Every run
**passed reconciliation**. None of these runs is part of `COMPARISON.md`.

The code is commit `610ade2` plus three uncommitted changes:
- **Method 1, merged completion steps (Checkout).**
  - The request that freezes the basket drives the attempt it just created and leased, without
    acquiring the lease again or re-reading the attempt between steps.
  - A terminal attempt skips the lease release. Finalize clears the lease in its own statement.
  - Prepare is one statement, and finalize is six statements whatever the basket size (set-based).
  - The receipt is built in memory instead of read back.
  - Start no longer reads the totals of an empty basket.
  - A happy-path completion now costs Checkout three local transactions (freeze, prepare,
    finalize) instead of five (with lease acquire and release), plus no extra reads. The saga
    states, recovery rules and fault points are unchanged.
- **Method 3, binary log off** on the four MySQL instances (`--disable-log-bin`, through
  `MYSQL_SERVER_OPTIONS`).
  - Nothing replicates from or restores with the binlog.
  - Committed data stays durable through the redo log, still flushed at every commit
    (`innodb_flush_log_at_trx_commit=1`).
- **Method 2, Checkout pool size** set through `CHECKOUT_DB_POOL_SIZE`.

## Results

| Run | Changes | Checkout pool | **Completed transactions** | Scans/s | Start p95 | Scan p95 | Complete p95 | Errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Baseline (measured median) | — | 10 | 23,511 | 1,355 | 304 ms | 296 ms | 357 ms | 0 |
| [`../current-code-2026-10-26`](../current-code-2026-10-26/) | Inventory fix only | 10 | 18,454 | 1,065 | 355 ms | 289 ms | 879 ms | 0 |
| [`m1-binlog-on-pool-10`](m1-binlog-on-pool-10/) | + method 1 | 10 | 19,961 | 1,156 | 170 ms | 168 ms | 498 ms | 10 scan 503s |
| [`m13-pool-10`](m13-pool-10/) | + methods 1 and 3 | 10 | **37,619** | 2,187 | 115 ms | 118 ms | 296 ms | 0 |
| [`m13-pool-15`](m13-pool-15/) | + methods 1 and 3 | 15 | **52,102** | 3,029 | 74 ms | 79 ms | 186 ms | 0 |
| [`m13-pool-20`](m13-pool-20/) | + methods 1 and 3 | 20 | **55,109** | 3,198 | 68 ms | 73 ms | 130 ms | 1 start, 5 scan 503s |
| [`m13-pool-30`](m13-pool-30/) | + methods 1 and 3 | 30 | **56,604** | 3,281 | 61 ms | 66 ms | 101 ms | 0 |

What the observations show:

| Run | Checkout pool: waiting / active / acquire | checkout-db COMMIT mean | Checkout server time, scan / complete | Inventory row-lock wait | CPU: checkout-db / Checkout / gateway (cores) |
|---|---|---:|---|---:|---|
| m1, binlog on, pool 10 | 186 / 9.8 / 116 ms | 4.23 ms | 121 / 382 ms | 55 s | 1.7 / 1.6 / 0.9 |
| m1 + m3, pool 10 | 167 / 9.3 / 58 ms | **1.44 ms** | 62 / 192 ms | 74 s | 3.9 / 3.2 / 1.8 |
| m1 + m3, pool 15 | 44 / 13.7 / 11 ms | 1.56 ms | 14 / 56 ms | 171 s | 4.7 / 4.0 / 2.3 |
| m1 + m3, pool 20 | 20 / 14.8 / 5.5 ms | 1.74 ms | 9 / 45 ms | 196 s | 4.9 / 4.0 / 2.4 |
| m1 + m3, pool 30 | 12 / 22.2 / 2.2 ms | 2.06 ms | 7 / 40 ms | 216 s | 5.0 / 4.2 / 2.5 |

## Reading

- **Method 1 alone, merged steps: +8% transactions.** Complete p95 fell from 879 to 498 ms, and
  start and scan p95 nearly halved. The pool was still saturated, with a 116 ms mean acquire time.
  At 4.2 ms per commit, the 10 connections remained the limit, but each completion now needs three
  of them instead of nine.
- **Method 3, binlog off: the largest single gain.** At the same pool of 10 it took completed
  transactions from 19,961 to 37,619 (+88%). Checkout's mean commit fell from 4.23 to 1.44 ms, so
  each connection is held far less time. Inventory's commits fell from 3.65 to 1.70 ms in the same
  way.
- **Method 2, pool size: +38% from 10 to 15, then small gains.** Going from 15 to 20 to 30 added
  +6% and then +3%. At 15, the pool wait drops from 58 ms to 11 ms. Beyond that the limits are:
  - commit cost, which rises with concurrency (1.44 → 2.06 ms);
  - CPU, with checkout-db at about 5 cores and Checkout at about 4;
  - the gateway hop: under load a scan spends about 41–44 ms more in the gateway than in Checkout,
    the same unexplained gap noted in Phase 8. Most of the scan p95 at pools 15–30 now comes from
    the gateway, not Checkout.
- **Every redesign target is beaten at pool 15 and above.** Against the measured baseline medians:
  - completed transactions: 52,102 against 23,511 (2.2×);
  - scan p95: 79 against 296 ms;
  - start p95: 74 against 304 ms;
  - complete p95: 186 against 357 ms.

## Attribution (constitution V)

These gains do not come from the service split alone. Each one has to be credited to its own
change before anything goes into `COMPARISON.md`:

- **Method 1** is a change to Checkout's code. It doesn't depend on the topology.
- **Method 3** is a MySQL configuration change (a declared difference: the baseline ran with the
  image's default binlog on). The baseline's shared instance would benefit from it as well. A fair
  comparison needs the baseline re-measured with `--disable-log-bin`.
- **Method 2** is the pool-size difference (F9). The baseline gained only +5% from pool 20, because
  `fulfill_checkout` row locks then limit it (see
  [`../../../baseline/exploratory/pool-20/NOTES.md`](../../../baseline/exploratory/pool-20/NOTES.md)),
  but it has not been measured with the binlog off.

## New finding: rare gateway 503s at the highest request rates

- **What happened:** the binlog-on run had 10 scans fail, and pool 20 had 1 start and 5 scans fail.
  Each one got `503 SERVICE_UNAVAILABLE` "The checkout service is unavailable." from the gateway.
  That message means the gateway's HTTP call to Checkout failed at the connection level, so
  Checkout never confirmed any effect. Reconciliation passed afterwards.
- **Likely cause:** a stale keep-alive connection. Checkout's Tomcat closes connections idle for
  20 s, and the gateway's JDK `HttpClient` can reuse one just as it closes. A non-idempotent POST is
  not retried. This was not confirmed (the containers were removed after each run).
- **Possible fixes:** set the gateway client's keep-alive timeout below Tomcat's
  (`jdk.httpclient.keepalive.timeout`), or retry only failures that happen before any byte was
  sent.
- It is outside methods 1–3 and was not changed here. SC-008 requires zero errors only under normal
  load.
