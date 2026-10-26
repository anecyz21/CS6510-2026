# Exploratory run: baseline with a Checkout pool of 20 (not a measured run)

`report-20261025-185836`: 200 stations × 180 s against tag `baseline/service-based-v1` (`f2875ad`),
using the same procedure and stock seed as the measured baseline runs. The only change is
Checkout's Hikari pool, raised from 10 to 20 through the environment variable
`SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=20`. It is set in an untracked copy of the baseline
compose file, `cs6510-baseline/domain-services/deployment/docker-compose.pool-20.yml`; no baseline
code changed. The observations confirm `hikaricp.connections.max = 20`. Reconciliation passed.

This run separates the effect of the pool size (defect F9) from the effect of the code. It is
compared with the redesign runs in
[`../../../redesign/exploratory/`](../../../redesign/exploratory/).

## Same pool size, both topologies (stress, one run each except the baseline at 10)

| | Baseline, pool 10 (median of 3) | **Baseline, pool 20** | Redesign with Inventory fix, pool 10 | **Redesign with Inventory fix, pool 20** |
|---|---:|---:|---:|---:|
| Successful scans/s | 1,355.1 | **1,428.3** | 1,057 | **1,721.3** |
| Scan p95 | 295.5 ms | **265.6 ms** | 290 ms | **174.9 ms** |
| Start p95 | 304.1 ms | **271.7 ms** | 347 ms | **197.0 ms** |
| Complete p95 | 356.5 ms | **409.0 ms** | 907 ms | **528.8 ms** |
| Errors | 0 | 0 | 0 | 0 |
| Checkout pool: mean waiting / acquire | 181 / 55 ms | 167 / 49 ms | 178 / 69 ms | 168 / 37 ms |
| Locking call, mean | `fulfill_checkout` 18.6 ms | `fulfill_checkout` **87.0 ms** | — | — |
| Row-lock wait per run | 406 s | **2,099 s** | 35 s (inventory-db) | 224 s (inventory-db) |
| Commit mean | 2.9 ms | 2.95 ms | 3.8 ms (checkout-db) | 5.2 ms (checkout-db) |
| Deadlocks / scan retries | 84 / 85 | 86 / 87 | 0 / 0 | 0 / 0 |
| Mean CPU, database(s) | MySQL 3.0 cores | MySQL 3.2 cores | checkout-db 2.2, inventory-db 0.5 | checkout-db 3.2, inventory-db 0.8 |

## Separating the pool-size gain from the code gain

Throughput, in successful scans per second:

| Step | Change | Gain |
|---|---|---:|
| Baseline, pool 10 → 20 | pool size alone, on the baseline | +73 (+5.4%) |
| Baseline pool 20 → redesign with fix, pool 20 | the code, at equal pool size | **+293 (+20.5%)** |
| Total: baseline 10 → redesign with fix, 20 | | +366 (+27.0%) |

- **The pool size helps the baseline very little.** With 20 connections, more completions reach
  `fulfill_checkout` at once. Its mean time rose from 18.6 ms to 87.0 ms, and row-lock waiting
  rose fivefold to 2,099 s, because every call locks the same hot SKUs. The baseline moves from
  being bound by its pool to being bound by hot-row locks, and complete p95 gets worse (409 ms).
- **The redesign uses the extra connections much better:** +664 scans/s from pool 10 to 20,
  against +73 for the baseline.
  - Its completion takes several short Checkout transactions and keeps Inventory's locks short
    (the tier 1 and tier 2 fix). Checkout connections, not row locks, were its limit.
  - The two topologies respond to the pool size differently, so neither pool value alone is a
    neutral basis for comparison.
- **At equal pool size (20 against 20), against SC-009-style targets** (redesign compared with the
  baseline at the same pool size):
  - scan p95 174.9 against 265.6 ms (0.66×, within the 0.90× target);
  - throughput 1,721 against 1,428 (+20.5%);
  - start p95 197.0 against 271.7 ms;
  - complete p95 528.8 against 409.0 ms: the redesign is still **worse** (1.29×).
- **Attribution (constitution V):**
  - Of the +27% throughput over the measured baseline, about one fifth (+73 scans/s) comes from
    the pool size alone and about four fifths (+293 scans/s) from the code.
  - "The code" includes the Inventory locking fix (tier 1 and tier 2). That is a locking design
    change inside Inventory, not something the service split itself provides.
  - Before the fix, at pool 10, the redesign was **slower** than the baseline: 916 against
    1,355 scans/s. Without the fix the architecture alone would not show this gain.

## Limits

- One exploratory run per configuration, except the baseline at pool 10, which is the median of 3.
- The redesign runs used uncommitted code (the T119 commit plus the Inventory fix).
- None of these runs replace the measured comparison in `../../../COMPARISON.md`.
