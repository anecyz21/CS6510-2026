# Domain services: deployment and evidence

This is the entry point for the independently deployable checkout services (feature
[`specs/004-domain-services`](../../specs/004-domain-services/spec.md)). Every design document,
contract, report and script is linked from here.

| You want | Go to |
|---|---|
| Deployables, ports and the Compose topology | [ARCHITECTURE.md: deployables and ports](ARCHITECTURE.md#deployables-and-ports) · [`docker-compose.yml`](docker-compose.yml) |
| Who owns which database, and the absence of cross grants | [ARCHITECTURE.md: data ownership and permissions](ARCHITECTURE.md#data-ownership-and-permissions) |
| Internal contracts (Catalog, Inventory, operations, Kafka event, public amendment) | [`specs/004-domain-services/contracts/`](../../specs/004-domain-services/contracts/README.md) |
| Completion saga and recovery rules | [ARCHITECTURE.md: completion](ARCHITECTURE.md#completion-saga-and-recovery) · [state machine](../../specs/004-domain-services/data-model.md#completion-attempt-state-machine) |
| Accepted scans and the analytics flow | [outbox, relay and Kafka](ARCHITECTURE.md#accepted-scans-outbox-relay-and-kafka) · [`ingest → window → rank-and-publish`](ARCHITECTURE.md#analytics-pipeline-ingest--window--rank-and-publish) |
| Performance evidence | [`reports/baseline/`](reports/baseline/OBSERVATIONS.md) · [`reports/redesign/`](reports/redesign/) · [`reports/COMPARISON.md`](reports/COMPARISON.md) · [exploratory runs](reports/redesign/exploratory/inventory-locking/NOTES.md) |
| Reconciliation | [`scripts/reconcile.sh`](scripts/reconcile.sh) (checks R-1 to R-6) · outputs in `reports/*/reconciliation-*.txt` |
| Full validation run | [`reports/validation-2026-10-26/`](reports/validation-2026-10-26/README.md) |
| Step-by-step scenarios | [quickstart V0–V11](../../specs/004-domain-services/quickstart.md) |

## Prerequisites

- JDK 21 (set `JAVA_HOME`), Maven 3.9, Docker with Compose v2, bash (Git Bash on Windows) and
  `jq`.
- `services.env`, copied from [`services.env.example`](services.env.example), with one password per
  database. It is never committed.
- Free host ports: 8080 (gateway), 8081–8084 (operator only), 3311–3314 (databases), 9092 (Kafka).

## Common commands

Run them from the repository root.

Build every deployable, start the stack and wait for readiness:

```bash
domain-services/deployment/scripts/start-services.sh
```

Return to empty stores (removes every volume, rebuilds and restarts):

```bash
domain-services/deployment/scripts/reset.sh
```

Check readiness of every database, Kafka and service:

```bash
domain-services/deployment/scripts/check-readiness.sh
```

Reconcile the settled state (R-1 to R-6); the last line is `RECONCILIATION PASS` or `FAIL`:

```bash
domain-services/deployment/scripts/reconcile.sh --wait-settled 60
```

Unit, contract and Testcontainers tests (no stack needed):

```bash
mvn -f domain-services/pom.xml verify
```

System tests against the running stack:

```bash
mvn -f domain-services/pom.xml -pl integration-tests -am -Psystem-tests test
```

Fault-injection tests need the override file. Start the stack with it:

```bash
docker compose -f domain-services/deployment/docker-compose.yml -f domain-services/deployment/docker-compose.faults.yml --env-file domain-services/deployment/services.env up -d
```

Then run the tests with `-Dstack.faults=true`:

```bash
mvn -f domain-services/pom.xml -pl integration-tests -am -Psystem-tests test -Dstack.faults=true
```

A measured run against either topology (see [`reports/README.md`](reports/README.md)):

```bash
domain-services/deployment/scripts/run-measurement.sh --topology redesign --compose-file domain-services/deployment/docker-compose.yml --env-file domain-services/deployment/services.env --stock-sql domain-services/deployment/provisioning/measured-stock.sql --profile stress --report-dir domain-services/deployment/reports/redesign
```

Compare baseline and redesign runs (writes `reports/COMPARISON.md`):

```bash
domain-services/deployment/scripts/compare-reports.sh
```

## Folder contents

| Path | Contents |
|---|---|
| `docker-compose.yml` | The measured topology: four private MySQL instances, Kafka, four domain services and the gateway |
| `docker-compose.faults.yml`, `toxiproxy/` | Fault-injection override for the V7 recovery tests only |
| `scripts/` | Start, reset, readiness, reconciliation, measurement, observation capture and comparison |
| `provisioning/` | Stock seeds for measured runs (`measured-stock.sql`, and `measured-stock-baseline.sql` for the baseline schema) and controlled restocking |
| `reports/` | Baseline, redesign, exploratory and historical workload evidence, plus validation runs |
| `tools/` | Diagnostic helpers (for example `HopProbe.java` for the gateway-hop finding) |

## Known limits

The measured comparison was taken before the Inventory locking fix and with Checkout's pool at 10.
[`COMPARISON.md`](reports/COMPARISON.md) records two SC-009 targets as **UNMET** (throughput and
complete p95) for that code. Since then:
- **Inventory locking fix:** Inventory no longer queues completions on hot SKUs.
- **Merged completion steps:** a completion costs Checkout three local transactions instead of
  five.
- **Binlog off:** the MySQL binary log is disabled.
- **Pool 15:** Checkout's pool default is now 15.

Exploratory runs with all four reached 52,102 stress transactions against the baseline's 23,511. See
[`throughput-methods/NOTES.md`](reports/redesign/exploratory/throughput-methods/NOTES.md), including
which part of the gain belongs to configuration rather than architecture.
