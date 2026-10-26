# Historical — inadequate stock, not baseline evidence

These reports record the database-coordinated service system as it ran on 2026-10-16. They are
**not** the pre-migration baseline and must not be compared with the microservice redesign. The
measured baseline is in [`../baseline/`](../baseline/), and the comparison is in
[`../COMPARISON.md`](../COMPARISON.md).

Why they cannot serve as baseline evidence
([research F8](../../../../specs/004-domain-services/research.md#findings-from-the-current-implementation)):

- **Inadequate stock.** The load client picks SKUs with a Zipf(1/rank) distribution over 2,000
  items. The top SKU therefore receives about 12.2% of scans: about 34,000 of the 281,724 stress
  scans, against a seed of 1,000 units. The stress run ended with 35 SKUs at `currentStock: 0`,
  so completions were silently partial.
- **Single run, no bottleneck observations.** Each workload ran once, with no record of
  connection-pool waits, lock waits, deadlock retries or resource use (FR-030).
- **Analytics ingested only the first 500 scans**
  ([research F1](../../../../specs/004-domain-services/research.md#findings-from-the-current-implementation)),
  so the popular-items results in these reports are not valid either.

The original notes follow unchanged.

## Original workload notes (2026-10-16)

The gateway at `http://localhost:8080` was exercised on 2026-10-16 with Docker Compose, MySQL 8.4, five Spring Boot service containers, and a fresh catalog/inventory seed of 2,000 SKUs. The unchanged Java load client used its default basket range of 1–20 items.

| Run | Report | Result |
| --- | --- | --- |
| Normal: 10 stations, 60 s | `report-20261016-043717.json` | 1,081 completed transactions; 11,194 scans; 17.83 transactions/s and 184.64 scans/s; zero errors. Scan mean/p95/p99: 48.38/53.23/57.63 ms. |
| Stress: 200 stations, 180 s | `report-20261016-044039.json` | 26,865 completed transactions; 281,724 scans; 148.21 transactions/s and 1,554.22 scans/s; zero errors. Scan mean/p95/p99: 105.85/260.84/378.95 ms. |

The stress run's start/complete mean/p95/p99 latencies were 110.94/266.96/385.12 ms and 123.92/300.08/430.56 ms, respectively. The normal run's start/complete mean/p95/p99 latencies were 47.23/51.99/67.59 ms and 9.41/16.28/22.75 ms.

Checkout retries MySQL deadlock victims up to eight times with capped exponential backoff. This addresses transient contention without treating a failed write as accepted. A previous four-attempt policy produced two scan errors during a normal-run startup burst; the recorded final runs have no errors.

After the stress run, `integration-tests/scripts/reconcile-inventory.sql` (kept at tag `baseline/service-based-v1`; removed from the redesign) returned no rows: all inventory remains non-negative and, for every SKU, initial stock minus available stock equals completed receipt-line quantity.
