# Event-driven measurements: notes

> **Status: not yet measured.** Docker was unavailable while the 005 implementation was written,
> so the runs below have not been made. This file already records the setup. Once the runs exist,
> fill in the graded reports, results and error explanations (tasks T004, T063–T065).

## Graded reports

| Profile | Command | Graded report (median scan p95 of 3 runs) |
|---|---|---|
| default | `cd load-client && java -cp out Main` (10 stations × 60 s, built-in defaults) | _pending: `default/report-<ts>.json`_ |
| stress | `cd load-client && java -cp out Main --stations=100 --duration=120` | _pending: `stress/report-<ts>.json`_ |

All runs, including the 004 comparison runs, are listed in [`COMPARISON.md`](COMPARISON.md), which
`../../scripts/compare-event-driven.sh` writes.

## How the runs are made

```bash
domain-services/deployment/scripts/run-measurement.sh --topology event-driven --compose-file domain-services/deployment/docker-compose.yml --env-file domain-services/deployment/services.env --stock-sql domain-services/deployment/provisioning/measured-stock.sql --profile default --report-dir domain-services/deployment/reports/event-driven/default
```

Run the same command with `--profile stress100 --report-dir …/event-driven/stress`. For the 004
comparison, use a worktree at tag `pre-event-driven` (see [README.md](README.md)) and
`--topology pre-event-driven`.

Each run resets every volume, rebuilds and starts the stack, applies the measured stock seed,
warms up for 30 s (report discarded), runs the unchanged load client, and then reconciles (R-1 to
R-9).

## Topology and settings (event-driven build)

| Setting | Value | Source |
|---|---|---|
| Deployables | gateway, Catalog, Checkout, Inventory, Analytics: one container each | `docker-compose.yml` |
| Databases | four private MySQL 8.4 instances, binlog off (as 004) | `docker-compose.yml` |
| Broker | Apache Kafka 3.8.1, one KRaft node, replication 1 | `docker-compose.yml` |
| `inventory.commands.v1` / `inventory.replies.v1` | 12 partitions each, key `attemptId` | owner `NewTopic` beans |
| `catalog.products.v1` | 1 partition, compacted | Catalog `KafkaConfig` |
| `checkout.accepted-scans.v1` | 1 partition (as 004) | Checkout `KafkaProducerConfig` |
| Command and reply producers | `acks=all`, idempotent, `linger.ms=0` | `messaging/KafkaConfig` |
| Listener concurrency | Inventory commands 6, Checkout replies 6, projections 1 | `messaging/KafkaConfig` |
| Outbox sending | right after commit, one sender thread per service; sweep every 1 s for rows unsent after 2 s | `messaging/OutboxSender` |
| Request deadline | 5 s, then `503`, and the attempt keeps settling | `checkout.completion.request-deadline` |
| Resend budget | first resend after 1 s, backoff 250 ms doubling to 5 s, cancel-after 30 s, attention after 20 sends | `checkout.recovery.*` |
| DB pools | Checkout 15, others 10 (as 004) | `docker-compose.yml` |
| Stock seed | `provisioning/measured-stock.sql` (as 004) | |
| Load-client request timeout | 10 s (client default) | |
| Host | _record CPU count, memory, OS and Docker version from the observations file_ | `observations-<ts>.json` |
| Commits | 004: `pre-event-driven` (`601ba53`); 005: _record the commit the graded runs used_ | |

## Results and explanation

_Pending the runs. Cover:_
- the scan and complete p95 change against `baseline-004`, from `COMPARISON.md`;
- every error, with its operation, count and cause;
- the `checkout.saga.roundtrip` p95 and per-group consumer lag, from the observations;
- why any difference may not be due to the architecture: run-to-run spread, warm-up, host load.
