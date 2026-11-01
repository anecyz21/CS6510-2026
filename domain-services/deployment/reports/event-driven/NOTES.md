# Event-driven measurements: notes

The 005 build, where services talk to each other only through Kafka, measured with the unchanged
load client and compared with the 004 build re-measured on the same host, profiles and gateway
binding. Every run is listed in [`COMPARISON.md`](COMPARISON.md).

## Graded reports

| Profile | Command | Graded report (median scan p95 of 3 runs) | Transactions | Errors | Reconciliation |
|---|---|---|---:|---:|---|
| default | `cd load-client && java -cp out Main` (built-in defaults: 10 stations × 60 s) | [`default/report-20261031-212155.json`](default/report-20261031-212155.json) | 8,185 | 0 | PASS |
| stress | `cd load-client && java -cp out Main --stations=100 --duration=120` | [`stress/report-20261031-213406.json`](stress/report-20261031-213406.json) | 30,946 | 0 | PASS |

The other two runs of each profile sit beside the graded one: default 3,301 and 8,220
transactions; stress 29,814 and 31,166. All six 005 runs had zero errors and passed
reconciliation R-1 to R-9.

## How the runs were made

```bash
domain-services/deployment/scripts/run-measurement.sh --topology event-driven --compose-file domain-services/deployment/docker-compose.yml --env-file domain-services/deployment/services.env --stock-sql domain-services/deployment/provisioning/measured-stock.sql --profile default --report-dir domain-services/deployment/reports/event-driven/default
```

The stress runs used the same command with `--profile stress100 --report-dir …/event-driven/stress`.
The 004 runs used `--topology pre-event-driven` and the compose file of a worktree at tag
`pre-event-driven`. That one file was edited in the worktree to publish the gateway the same way
(see below). Each run:
1. resets every volume, rebuilds the images and starts the stack;
2. applies the measured stock seed;
3. warms up for 30 s, discarding that report;
4. runs the unchanged load client;
5. reconciles.

The runs were made one after another on 2026-10-31 (21:03 to 21:34 local time), 004 first.

## Gateway binding

Both builds publish the gateway as `127.0.0.1:8080:8080`. On Docker Desktop for Windows, the
all-interfaces binding `8080:8080` stalls every POST with a body from the host by about 44 ms (004
[`GATEWAY-HOP.md`](../redesign/GATEWAY-HOP.md)). Each scan is a POST, and the client is
closed-loop, so that stall, not the services, used to set the transaction count.

The first measured set kept `8080:8080` and produced 1,116 (default) and 19,491 (stress)
transactions for 005. It is kept, with its own comparison, in
[`archive-all-interfaces-binding/`](archive-all-interfaces-binding/COMPARISON.md). The single-run
check that showed the effect is in [`localhost-binding/`](localhost-binding/).

## Environment

| Setting | Value |
|---|---|
| Host | 24 CPUs, 15.5 GiB for Docker Desktop (WSL2 kernel 6.18), Docker 29.1.3 |
| Other containers | none running (an unrelated `cs6510-fixture` project was stopped first because it was crash-looping) |
| Commits | 004: tag `pre-event-driven` (`601ba53`) with only the gateway port binding changed in the worktree. 005: `6bc3b7a` |
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
| Transactions | 9,480 | 8,185 | 34,373 | 30,946 |
| Successful scans/s | 1,668 | 1,434 (−14%) | 2,998 | 2,705 (−10%) |
| Scan p95 / p99 (ms) | 5.4 / 8.8 | 6.0 / 10.5 | 45.6 / 63.0 | 20.1 / 30.2 |
| Complete p95 / p99 (ms) | 29.7 / 40.1 | 42.0 / 56.2 | 133.7 / 164.8 | 584.6 / 760.4 |
| Errors | 0 | 0 | 0 | 0 |

**Completion is what the event-driven design costs.** A completion now makes four broker hops:
reserve command, reserve reply, commit command, commit reply. Each hop goes through an outbox
write, an after-commit send, a broker acknowledgment and a consumer poll, where 004 made two direct
HTTP calls. Checkout's `checkout.saga.roundtrip` timer measures from the reserve command being
written to the terminal reply being applied:

| Graded run | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|
| default | 19 ms | 35 ms | 47 ms | 124 ms |
| stress | 217 ms | 569 ms | 770 ms | 1,032 ms |

At default load the saga adds about 12 ms to completion p95. Under stress it accounts for almost
all of the 585 ms completion p95. One likely cause, not yet proven, is that all of a service's
outbox rows go through **one sender thread**. That thread is what guarantees per-attempt ordering,
and with 100 stations completing at once it becomes a queue.

**Scans stay fast, and are faster than 004 under stress.** Both builds price scans from a local
catalog copy. Under stress, 005's scan p95 is less than half of 004's (20 ms against 46 ms). The
likely reason is that stations spend more of their time waiting for completions, so fewer scans
compete for Checkout's database at once. This comes from the closed-loop client, not from a faster
scan path.

**Throughput is 10–14% lower.** Each station waits for its completion before the next
transaction, so the slower completion leaves fewer transactions per station.

**No retries were needed.** `checkout.recovery.resends` stayed at zero in every graded run.
Every reply arrived before its 1-second resend time.

**One outlier run.** The first 005 default run (`default/report-20261031-211945.json`, 3,301
transactions) was slower throughout: its saga p95 was 108 ms against 35 ms in the next two runs.
It used the same reset, rebuild and warm-up as every other run. It was the first run of the 005
images after the 004 set, and its cause is not known. It is included in the medians and in
`COMPARISON.md`, not dropped.

**What is not shown:**
- **No architectural speed-up.** The point of 005 is decoupling: any one service can be down
  without stopping the others, as the system tests show. It is not raw speed, and the numbers
  show 005 slower than 004.
- **Consumer lag was not captured.** The `kafka-consumer-groups.sh` sample came back empty in these
  runs. The reconciliations prove every group had drained by the end of each run (R-6, R-7).

## Possible follow-up (not done)

Send each partition's outbox rows on their own thread, keeping per-attempt order, so the outbox
sender stops being a single queue under load. Then re-measure the stress profile.
