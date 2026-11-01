# Quickstart: Validating Event-Driven Communication

Runnable checks that prove [spec.md](spec.md) end to end. Message shapes are in
[contracts/](contracts/README.md), and tables and transitions are in
[data-model.md](data-model.md). Paths are relative to the repository root unless noted. The
scripts are the 004 ones in `domain-services/deployment/scripts/`, extended as described in
[research.md](research.md) R11 and R13.

## Q0. Prerequisites and setup

- JDK 21, Maven 3.9, and Docker with Compose v2, on the host used for every measured run.
- `domain-services/deployment/services.env` created from `services.env.example`. It holds the
  four owner credentials and nothing else.
- The build compiles. The two broken working-copy edits noted in [plan.md](plan.md) must be
  fixed first.

```bash
mvn -q -f domain-services/pom.xml -DskipTests package
```

```bash
domain-services/deployment/scripts/start-services.sh
```

```bash
domain-services/deployment/scripts/check-readiness.sh
```

**Expected**: all five apps report readiness `UP`. Each domain service's readiness detail lists
`db`, `kafka`, and, for Checkout, Inventory, and Analytics, `catalogProjection` as
`UP (CURRENT)`.

## Q1. Independent deployment and catch-up (gate 1, US3 scenario 4)

1. Stop Checkout, delete its projection (`POST /internal/v1/ops/catalog-projection/rebuild` on
   port 8082), and start it again.
2. Watch `/actuator/health/readiness` on port 8082.

**Expected**: readiness is `OUT_OF_SERVICE (REPLAYING)` until the projection reaches the end
offset captured at startup, then `UP`. The projection holds 2,000 SKUs, and `topic_offset`
equals the end offset minus 1. Repeat for Inventory (8083) and Analytics (8084).

## Q2. No direct communication between domain services (gate 2, FR-001, FR-002, FR-022, SC-001)

```bash
mvn -q -f domain-services/pom.xml -pl integration-tests -Dtest=NoInterServiceHttpTest,ModuleBoundaryTest,PrivateStoreAccessTest test
```

**Expected**: the tests pass. As a manual check, `docker compose config` shows `CATALOG_URL` and
`INVENTORY_URL` on no domain service, and only the gateway has `SERVICES_*_URL`. As a negative
check, adding a `RestClient` field to any domain service makes `NoInterServiceHttpTest` fail.

## Q3. Public contract unchanged (gate 3, FR-004, US1)

Run the 004 public-contract suite and the completion suite, now driven through the message saga.

```bash
mvn -q -f domain-services/pom.xml -pl integration-tests -Dtest='contract.*,completion.*' verify
```

**Expected**: every 004 scenario passes unchanged: partial purchase with per-attempt receipt,
`409 INSUFFICIENT_STOCK`, `409 TRANSACTION_BUSY`, joined completion, `409 TRANSACTION_NOT_OPEN`,
and an unknown SKU rejected with the existing error. The Inventory consumer group
`inventory-reservations` shows the reserve and commit records for the completed attempts.

## Q4. Duplicate delivery of every message type (gate 6, US2 scenario 3, SC-007)

Start the stack with the faults override and `MESSAGING_FAULTS_DUPLICATE_SENDS=true`, so every
outbox sender and the scan relay send each record twice. Run the default workload, let it
settle, and reconcile.

```bash
domain-services/deployment/scripts/reconcile.sh
```

**Expected**: reconciliation passes for 100% of SKUs. Receipt lines are not duplicated, the
Analytics counts equal the accepted scans, and `checkout.saga.reply.ignored{reason="stale"}` is
greater than zero, which shows the duplicate replies arrived and were ignored.

## Q5. One service down at a time (US2 scenarios 1–2, US3, SC-005, SC-006)

While a default workload runs, stop each service for the stated time, then start it again:

| Stop | For | Expected while stopped | Expected after restart |
|---|---|---|---|
| `catalog` | 60 s | 100% of scans succeed with correct captured prices | Readiness `UP`; nothing to catch up |
| `analytics` | 60 s | Scans and completions succeed; consumer lag on `analytics-scan-ingest` grows | Lag returns to 0, and every missed scan is counted once |
| `inventory` | 20 s | Scans succeed; each completion returns `503 SERVICE_UNAVAILABLE` within 5 s | Within 60 s every attempt is `FINALIZED` or `REJECTED`. A repeated `complete` on a finalized, empty basket returns `409 TRANSACTION_NOT_OPEN`, with no second sale |
| `inventory` | 60 s | As above | Attempts that waited past `cancel-after` (30 s) end `CANCELLED`: no stock change, basket still open with the same items. A new `complete` succeeds |

Then reconcile (as in Q4). **Expected**: pass, with no unresolved attempts or leftover
reservations.

## Q6. Checkout crash points (gate 5, US2 scenario 5)

With the faults profile, halt Checkout at each `HaltingFaultInjector` checkpoint in turn
(`AFTER_FREEZE`, `AFTER_RESERVED_APPLIED`, `AFTER_COMMITTED_APPLIED`), then restart it.

**Expected**: each attempt resumes under its original `attemptId`, and no second attempt is
created for the same frozen basket. Unsent `command_outbox` rows are swept and sent, and the
attempt settles exactly once. Reconciliation passes.

## Q7. Broker restart under load (gate 6, US2 scenario 4, SC-008)

Start a stress run (Q10), and 40 s in, restart the broker:

```bash
docker compose -f domain-services/deployment/docker-compose.yml restart kafka
```

**Expected**:
- **While Kafka is down**: scans keep succeeding and completions return `503`.
- **Within 60 s of Kafka being healthy**: every outbox's pending count is 0, all consumer lag is
  0, and no attempt is unresolved.
- **Reconciliation** passes, and no accepted scan is missing from Analytics.

## Q8. Replay, poison messages, and dead-letter topics (gate 6, FR-017, SC-009)

1. **Replay**: rebuild every projection (Q1) and confirm that the names in
   `/inventory/low-stock` and `/analytics/popular-items` match Catalog.
2. **Ranking**: run the 004 1,500-scan ranking test.

   ```bash
   mvn -q -f domain-services/pom.xml -pl integration-tests -Dtest='analytics.*' verify
   ```

3. **Poison messages**: run `messaging.PoisonMessageIT`. It publishes a malformed record to
   `inventory.commands.v1` and to `inventory.replies.v1`, then a valid command.

**Expected**:
- The publication at scan 1,500 matches scans 501–1,500, before and after a replay.
- Each malformed record appears once in `inventory.dlt.v1` or `checkout.dlt.v1`, with the
  `dlt-*` headers, within about 7 s.
- The valid command after it is processed.
- `messaging.dlt.published` increments by 1 per service.

## Q9. Graded run: default parameters (gate 4, FR-023, SC-002)

```bash
domain-services/deployment/scripts/run-measurement.sh --topology event-driven --compose-file domain-services/deployment/docker-compose.yml --stock-sql domain-services/deployment/provisioning/measured-stock.sql --profile default --report-dir domain-services/deployment/reports/event-driven/default
```

The `default` profile runs the unchanged client with no workload flags, which is the same as
`cd load-client && java -cp out Main`.

**Expected**:
- Three `report-<ts>.json` files, each with zero errors, plus their observations and
  reconciliation files.
- Every reconciliation reads `RECONCILIATION PASS`.

## Q10. Graded run: stress (gate 4, FR-024, SC-003)

Run the command in Q9 again, with `--profile stress100` and
`--report-dir domain-services/deployment/reports/event-driven/stress`. That profile runs exactly
`java -cp out Main --stations=100 --duration=120`.

**Expected**:
- At least 99% of operations succeed in each report, and every error is categorized in
  `NOTES.md`.
- Reconciliation passes.
- Observations include `checkout.saga.roundtrip` p95, consumer lag, and outbox pending counts.

For comparison, repeat Q9 and Q10 at tag `pre-event-driven` with `--topology pre-event-driven`
and the report directory `reports/event-driven/baseline-004/`. That is the 004 build, unchanged.

## Q11. Submission artifacts (gate 7, FR-025, FR-026, SC-010, SC-011)

1. `domain-services/deployment/scripts/compare-reports.sh` writes
   `reports/event-driven/COMPARISON.md`.
2. `NOTES.md` names the graded default and stress reports. It also records topology,
   partitions, concurrency, producer settings, pool sizes, stock seed, and hardware, and states
   the scan and complete p95 deltas against `baseline-004` without architectural claims.
3. `domain-services/deployment/scripts/render-diagram.sh` regenerates
   `domain-services/deployment/architecture.svg` from the Mermaid source in `ARCHITECTURE.md`.

**Expected**:
- From the root `README.md`, a reviewer reaches both graded JSON reports and the diagram in
  under 2 minutes.
- The diagram shows the gateway, Catalog, Checkout, Inventory, Analytics, four MySQL
  databases, and Kafka, with each topic's publish and consume arrows labeled.
- `git ls-files` lists both JSON reports and the SVG.
