# Checkout database pool size (task T118)

**Chosen value: `CHECKOUT_DB_POOL_SIZE=10`**, the same as the baseline. It is the default in
[`../../docker-compose.yml`](../../docker-compose.yml) and is used by every measured redesign run.

## Evidence

### Baseline (T013, [`../baseline/OBSERVATIONS.md`](../baseline/OBSERVATIONS.md))

Under stress the baseline's Checkout pool of 10 was the dominant bottleneck. About 180 of 200
stations waited for a connection, all 10 connections were busy, and the mean acquire time was about
54 ms (about 46% of Checkout's scan time). Each scan held a connection for about 6 ms: about eight
round trips, including the cross-schema price read and a 2.9 ms commit, plus a second acquisition
for the basket totals.

### Redesign exploratory stress run

One run: 200 stations × 180 s, with `CHECKOUT_DB_POOL_SIZE=10` and all other pools at 10.
The files are in [`exploratory/pool-10/`](exploratory/pool-10/) (`report-20261025-174458`). This
run is **not** part of the comparison.

| Pool (10 connections) | Mean waiting | Max waiting | Mean active | Mean acquire | Max acquire | Timeouts |
|---|---:|---:|---:|---:|---:|---:|
| Checkout | 5.3 | 56 | 8.6 | **3.4 ms** | 160 ms | 0 |
| Inventory | 135.3 | 156 | 9.5 | **761 ms** | 2,505 ms | 0 |
| Catalog, Analytics | 0 | — | < 1 | — | — | 0 |

- **Checkout's pool is no longer the bottleneck.** A scan now uses one local transaction and reads
  prices from Checkout's own catalog projection, so the mean wait fell from about 54 ms to 3.4 ms
  with the same 10 connections. Scan p95 was 70 ms against 295.5 ms in the baseline.
- **Inventory is the new bottleneck, and its cause is row locking, not pool size.**
  - Inventory's `SELECT … FROM inventory_positions WHERE sku IN (…) ORDER BY sku FOR UPDATE` ran
    30,651 times: once in reserve and once in commit for every completion. It spent about 1,502 s
    waiting for row locks in total, a mean of 49 ms per statement.
  - InnoDB recorded about 29,600 row-lock waits, about 1,505 s in total, against about 406 s in a
    baseline stress run.
  - inventory-db used about 0.7 of 24 cores, so it was not CPU-bound.
  - The load client's Zipf SKU choice puts the same few hot SKUs in almost every basket. Each
    transaction locks its SKUs in SKU order and holds them until its commit (about 3 ms of redo
    flush), so completions queue behind each other on those rows.
  - A larger Inventory pool would only move the queue from Hikari into InnoDB lock waits.
- **CPU is not the limit anywhere.** Mean container CPU was: Checkout 1.6 cores, checkout-db 1.7,
  gateway 0.7, inventory-db 0.7, Inventory 0.5, Kafka 0.3 and Analytics 0.1.

## Why 10

1. **10 is enough.** Checkout's pool waits are short (3.4 ms mean, no timeouts), so a larger pool
   cannot improve scan or start latency much.
2. **No configuration difference.** Keeping the baseline value removes defect F9 (a different
   pool size) as a source of difference. Any change in the comparison then comes from the code
   and topology, not from pool tuning (constitution V).
3. **A larger pool would not help completion.** Completion's limit is Inventory's row locking on
   hot SKUs, and no Checkout pool size changes that.

Inventory, Catalog and Analytics keep their default of 10 for the same reasons.
