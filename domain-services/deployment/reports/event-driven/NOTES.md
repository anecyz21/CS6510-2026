# Event-driven measurements: notes

The 005 build, where services talk to each other only through Kafka, measured with the unchanged
load client and compared with the 004 build re-measured on the same host and profiles. Every run is
listed in [`COMPARISON.md`](COMPARISON.md).

## Graded reports

| Profile | Command | Graded report (median scan p95 of 3 runs) | Errors | Reconciliation |
|---|---|---|---:|---|
| default | `cd load-client && java -cp out Main` (built-in defaults: 10 stations × 60 s) | [`default/report-20261031-194243.json`](default/report-20261031-194243.json) | 0 | PASS |
| stress | `cd load-client && java -cp out Main --stations=100 --duration=120` | [`stress/report-20261031-195132.json`](stress/report-20261031-195132.json) | 0 | PASS |

The other two runs of each profile sit beside the graded one. All six 005 runs had zero errors and
passed reconciliation R-1 to R-9.

## How the runs were made

```bash
domain-services/deployment/scripts/run-measurement.sh --topology event-driven --compose-file domain-services/deployment/docker-compose.yml --env-file domain-services/deployment/services.env --stock-sql domain-services/deployment/provisioning/measured-stock.sql --profile default --report-dir domain-services/deployment/reports/event-driven/default
```

The stress runs used the same command with `--profile stress100 --report-dir …/event-driven/stress`.
The 004 runs used `--topology pre-event-driven` and the compose file of a worktree at tag
`pre-event-driven`. Each run:
1. resets every volume, rebuilds the images and starts the stack;
2. applies the measured stock seed;
3. warms up for 30 s, discarding that report;
4. runs the unchanged load client;
5. reconciles.

The runs were made one after another on 2026-10-31 (19:23 to 19:55 local time), 004 first.

## Environment

| Setting | Value |
|---|---|
| Host | 24 CPUs, 15.5 GiB for Docker Desktop (WSL2 kernel 6.18), Docker 29.1.3 |
| Other containers | none running (an unrelated `cs6510-fixture` project was stopped first because it was crash-looping) |
| Commits | 004: tag `pre-event-driven` (`601ba53`). 005: `c4028ce` (code identical to the validated build; the commit adds only measurement-script fixes) |
| Deployables | gateway, Catalog, Checkout, Inventory, Analytics: one container each, four private MySQL 8.4 instances (binlog off), one Kafka 3.8.1 KRaft node |
| DB pools | Checkout 15, others 10 (same in both builds) |
| Stock seed | `provisioning/measured-stock.sql` (same in both builds) |
| 005 topics | `inventory.commands.v1` and `inventory.replies.v1` with 12 partitions each, key `attemptId`; `catalog.products.v1` with 1 partition, compacted; `checkout.accepted-scans.v1` with 1 partition |
| 005 producers and listeners | command and reply producers `acks=all`, idempotent, `linger.ms=0`; listener concurrency 6 for Inventory commands and 6 for Checkout replies |
| 005 outbox sending | right after commit, one sender thread per service; a 1 s sweep catches rows unsent after 2 s |
| Request deadline | 5 s, then `503` (same in both builds); load-client request timeout 10 s |

## Results (medians of 3 runs)

| Metric | 004 default | 005 default | 004 stress | 005 stress |
|---|---:|---:|---:|---:|
| Scan p95 / p99 (ms) | 51.0 / 52.5 | 50.5 / 52.4 | 58.0 / 72.1 | 60.0 / 75.4 |
| Successful scans/s | 191.3 | 190.8 | 1,779 | 1,686 (−5.2%) |
| Complete p95 / p99 (ms) | 25.2 / 35.7 | 29.4 / 39.9 | 58.7 / 106.4 | 129.9 / 321.4 |
| Errors | 0 | 0 | 0 | 0 |

**Scans are unchanged** (within 1% at default and 3.4% at stress). Both builds price scans from
a local catalog copy. In 004 that copy was loaded over HTTP; in 005 it is built from
`catalog.products.v1`. Either way, no scan leaves Checkout.

**Completion is slower under stress.** At default load it costs about 4 ms more at p95. At 100
stations its p95 is about 2.2× the 004 figure (59 → 130 ms) and its p99 about 3× (106 → 321 ms).
That is expected, because a completion now makes four broker hops: reserve command, reserve reply,
commit command, commit reply. Each hop goes through an outbox write, an after-commit send, a
broker acknowledgment and a consumer poll, where 004 made two direct HTTP calls. Checkout's
`checkout.saga.roundtrip` timer measures from the command being written to the terminal reply
being applied:

| | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|
| default run | 16 ms | 23 ms | 28 ms | 130 ms |
| stress run | 27 ms | 62 ms | 96 ms | 821 ms |

Under stress the saga accounts for about half of the 130 ms the client sees at p95. The rest is
the request thread waiting to be woken, plus the gateway hop and HTTP queuing. One likely
contributor, not yet proven, is that all of a service's outbox rows go through **one sender
thread**. That thread is what guarantees per-attempt ordering, and with 100 stations completing
at once it becomes a queue.

**Throughput is 5% lower under stress.** The load client is closed-loop: each station waits for
its completion before starting the next transaction. So the slower completion leaves fewer
transactions per station in 120 s (19,491 vs 20,464 median). Scan latency itself did not change.

**No retries and no duplicates were needed.** `checkout.recovery.resends` and
`checkout.saga.reply.ignored` stayed at zero in both graded runs. Every reply arrived before its
1-second resend time.

**What is not shown:**
- **No architectural speed-up.** The point of 005 is decoupling: any one service can be down
  without stopping the others, as the system tests show. It is not raw speed, and the
  numbers do not show an improvement.
- **Consumer lag was not captured.** The `kafka-consumer-groups.sh` sample failed during these
  runs (`OCI runtime exec failed`), because Git Bash rewrote the container path. The script is
  fixed for later runs. The reconciliations prove every group had drained by the end of each run
  (R-6, R-7).
- **Run-to-run spread is small but real.** Stress complete p95 ranged 121 to 140 ms across the
  three 005 runs, and 58.6 to 60.5 ms across the 004 runs.

## Possible follow-ups (not done)

These came out of the measurements and are not part of the graded work:
- Send each partition's outbox rows on their own thread, keeping per-attempt order, so the outbox
  sender stops being a single queue under load. Then re-measure the stress profile.

## Experiment: gateway published on loopback only

After the graded runs, `docker-compose.yml` was changed to publish the gateway as
`127.0.0.1:8080:8080` instead of `8080:8080`. On Docker Desktop for Windows, the all-interfaces
binding stalls every POST with a body from the host by about 44 ms (004
[`GATEWAY-HOP.md`](../redesign/GATEWAY-HOP.md)). The services and the load client are unchanged.
One run per profile on the 005 build (commit after `43b27f5`):

| Profile | Report | Transactions | Scan mean / p95 (ms) | Complete p95 (ms) | Errors | Reconciliation |
|---|---|---:|---|---:|---:|---|
| default | [`localhost-binding/default/report-20261031-205123.json`](localhost-binding/default/report-20261031-205123.json) | 8,179 (was 1,116) | 4.1 / 5.9 | 40.6 | 0 | PASS |
| stress | [`localhost-binding/stress/report-20261031-205443.json`](localhost-binding/stress/report-20261031-205443.json) | 30,248 (was 19,491) | 10.8 / 20.5 | 620.8 | 0 | PASS |

Without the stall, the closed-loop client sends requests much faster:
- **Default:** about 7× the transactions.
- **Stress:** completion becomes the limit. Its p95 rises to 621 ms as many more purchases queue on
  each service's single outbox sender thread, which is the follow-up listed above.

These are single runs, and the 004 build was not re-measured with this binding. The graded
reports and the 004 comparison above therefore keep the original `8080:8080` binding, and the two
sets must not be compared with each other.
