# Quickstart & Validation Guide: Independently Deployable Checkout Microservices

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Contracts**: [contracts/](contracts/README.md)

This guide lists runnable scenarios that prove the feature end to end. It names the scripts and
test classes that implementation will create (see the plan's structure). It does not contain
their code. Each scenario names the requirement, success criterion or quality gate it evidences.

| Scenario | Proves | Gate |
|---|---|---|
| V0 | Environment and reset | — |
| V1 | Independent build, start, migrate, readiness (US3-1, SC-002) | 1 |
| V2 | Private-store denial and module boundaries (US3-2, FR-002/003/028) | 1, 2 |
| V3 | Gateway is transport-only (US3-4, FR-005) | 2 |
| V4 | Public contract plus amendments A1–A6 (FR-006, SC-001) | 3 |
| V5 | Partial, per-attempt receipt, zero-stock, busy scan (US1, SC-001, SC-003) | 5 |
| V6 | Concurrent last-unit competition (US1-5, SC-003) | 5 |
| V7 | Interruption, lost response, Inventory outage (US2, SC-004) | 5 |
| V8 | Analytics ordering, replay, duplicates, outages (US4, SC-006/007/010) | 6 |
| V9 | Settled reconciliation (FR-019, SC-005) | 5 |
| V10 | Baseline vs redesign performance (US5, SC-008/009) | 4 |
| V11 | Documentation entry point (FR-033, SC-011) | 7 |

## V0: Prerequisites and reset

- JDK 21, Maven 3.9+, and Docker with Compose v2. Bash is required for scripts; Git Bash works on
  Windows.
- Ports free: 8080 (gateway); 3311–3314 (databases, operator access); 9092 (Kafka, host access).
- Copy `domain-services/deployment/services.env.example` to `services.env` and fill in owner
  passwords. `services.env` is gitignored and never committed.

```bash
mvn -q -f domain-services/pom.xml -DskipTests package
```

```bash
docker compose -f domain-services/deployment/docker-compose.yml --env-file domain-services/deployment/services.env up -d --build
```

```bash
domain-services/deployment/scripts/check-readiness.sh
```

`domain-services/deployment/scripts/reset.sh` removes all volumes, recreates every service from
empty databases and an empty topic, and re-runs readiness. Every V5–V10 run starts from a reset.

**Expected**: four `*-db` containers, `kafka`, `catalog`, `checkout`, `inventory`, `analytics` and
`gateway` are healthy. Each `/actuator/health/readiness` returns `UP`.

## V1: Independent deployment (Gate 1)

1. Stop every application container, then start **only** `checkout-db` and `checkout`.
   **Expected**: Checkout readiness is `UP` (own DB plus migrations). Health detail reports
   `inventory: DOWN`, `catalogProjection: EMPTY` and `kafka: DOWN`.
2. Repeat for Catalog, Inventory and Analytics, one at a time.
3. Redeploy only Inventory while the rest are running (`docker compose up -d --build inventory`).
   **Expected**: no other container restarts, and V4's contract tests still pass afterwards
   (US3-3).

Automated: `integration-tests` → `deployment/IndependentStartupTest`.

## V2: Private stores and boundaries (Gates 1–2)

```bash
mvn -f domain-services/pom.xml -pl integration-tests -Dtest='PrivateStoreAccessTest,ModuleBoundaryTest' verify
```

**Expected**:
- **Private stores**: each service account fails to authenticate or is denied on every other
  service's instance, including former view, procedure and outbox objects, which no longer exist
  anywhere.
- **Module boundaries**:
  - no domain module depends on another domain module;
  - no SQL in one service names another service's schema;
  - `service-contracts` has no persistence types;
  - the old `NoDirectDomainCallsTest` is gone.

## V3: Gateway is transport-only (Gate 2)

`ModuleBoundaryTest` asserts the gateway has no `DataSource`, no JDBC or Kafka dependency, and no
classes outside routing and error translation.

Manual check: stop `checkout`, then call `POST /transactions`. **Expected**:
`503 {"error":"SERVICE_UNAVAILABLE",…}` (amendment A6).

## V4: Public contract conformance (Gate 3)

```bash
mvn -f domain-services/pom.xml -pl integration-tests -Dtest='FullGatewayContractTest' verify
```

**Expected**:
- **All seven operations**: responses validate against the amended
  `spec/self-checkout-openapi.yaml` (v1.2.0), and each error code in the
  [error catalog](contracts/public-api-amendment.md#error-catalog-after-amendment) is produced by
  its trigger.
- **Names**: low-stock and popular-items `name` values equal catalog names, not SKUs.
- **Status**: no response carries a status outside `OPEN/COMPLETED/CANCELLED`.

Run the unchanged mock-server comparison if the course harness requires it. The load client and
mock server source are not modified.

## V5: Partial purchase, per-attempt receipts, zero stock, busy scan (US1)

Setup: reset, then use Inventory's provisioning SQL (owner credentials) to set `SKU-000001` = 2
and `SKU-000002` = 1.

| Step | Action | Expected |
|---|---|---|
| 1 | Start a transaction and scan A three times and B once | Four `200` scan responses. `itemCount` reaches 4. Inventory physical and reserved stock are unchanged (US1-1) |
| 2 | Complete | `200` receipt: lines A×2 and B×1, `itemCount=3`, total = 2·pA + pB (exact cents). `GET /transactions/{id}` shows `OPEN`, `itemCount=1` (US1-3) |
| 3 | Complete again (A has 0 stock) | `409 INSUFFICIENT_STOCK`. Basket unchanged, stock unchanged, no new receipt (US1-6) |
| 4 | Provision +1 A, then complete | `200` receipt for the **same** `transactionId` with only A×1, `itemCount=1`, total = pA. `startedAt` unchanged, new `completedAt`. Transaction `COMPLETED` (US1-4) |
| 5 | Complete again | `409 TRANSACTION_NOT_OPEN`, with no writes (US2-3) |
| 6 | Pause Inventory, complete a new basket, and scan the same transaction during the attempt | Scan returns `409 TRANSACTION_BUSY` immediately. The basket and outbox counts are unchanged (US2-4, SC-001) |

Automated: `completion/PartialFulfillmentTest`, `completion/BusyScanTest`.

## V6: Concurrent competition (US1-5)

`completion/ConcurrentLastUnitTest` covers two cases:
- 50 transactions on separate stations each hold one unit of a SKU with stock 1, plus one unit
  of a well-stocked SKU, and all complete simultaneously.
- 200 parallel completions run against random small stocks.

**Expected**:
- Exactly one receipt contains the contested unit.
- Every other basket keeps it and still buys the well-stocked SKU.
- No position goes negative, and V9 passes.

## V7: Interruption, lost response and outage recovery (US2, SC-004)

Run with the `fault-injection` profile on Checkout and the Compose `faults` profile (Toxiproxy
between Checkout and Inventory).

| Case | Injection | Expected after the dependency or process is healthy again (≤ 60 s) |
|---|---|---|
| a | Halt Checkout at `AFTER_FREEZE` | Recovery reserves and drives forward, **or** cancels after `cancel-after`. Never two attempts for the transaction |
| b | Halt at `AFTER_RESERVE_RESPONSE` | Recovery repeats reserve (same result), prepares, commits and finalizes, **or** cancels and releases. Reserved stock returns to 0 |
| c | Halt at `AFTER_PREPARE` | Forward only: commit, then finalize. One receipt |
| d | Halt at `AFTER_COMMIT_RESPONSE` | Commit repeat is idempotent, then finalize. Exactly one receipt and one stock decrement (US2-2) |
| e | Toxiproxy drops Inventory's commit response | Client gets `503` within 5 s. Recovery finalizes once. A later complete returns `409 TRANSACTION_NOT_OPEN` or buys only the remaining units (US2-3) |
| f | Inventory stopped for 45 s during completions | Each request returns `503 SERVICE_UNAVAILABLE` within ≤ 5 s (+ tolerance). Attempts remain listed by `GET /internal/v1/ops/completion-attempts`. After restart they resolve with no unexplained attempt or reservation (US2-6) |
| g | Concurrent duplicate completions for one transaction | One attempt id. All callers receive the same outcome (A5) |
| h | Cancel races commit (Toxiproxy delay plus forced `cancel-after` = 1 s in test config) | Exactly one of COMMITTED→FINALIZED or CANCELLED. Never both (FR-017) |

Automated: `completion/InterruptionRecoveryTest`, `completion/LostResponseTest`,
`completion/InventoryOutageTest`. Each test ends with V9.

## V8: Analytics durability and ordering (US4, Gate 6)

1. **Known sequence**: `analytics/KnownSequenceRankingTest` sends 1,500 accepted scans
   sequentially in a fixed SKU pattern. **Expected**: the publication at `windowEnd=1500` has
   `windowStart=501` and exactly the expected counts and ranks, with ties ordered by SKU ascending
   (SC-006). The publication at 1 and at 500 shows partial windows.
2. **Replay and duplicates**: reset the `analytics-scan-ingest` offsets to earliest and re-run the
   relay over already-published rows by clearing `published_at` in a test. **Expected**: identical
   `sequence_number`s, boundaries, counts and ranks, and `ingested_scans` count unchanged.
3. **Outage ≥ 10,000 scans**: `analytics/OutageRecoveryTest` stops Analytics, then Kafka, while
   10,000+ scans are accepted, keeps scanning after both restart, then waits for lag 0. **Expected**:
   zero lost or double-counted scans, and Checkout's outbox total equals Analytics' ingested count
   (SC-007).
4. **30-second interruption during checkout traffic**: stop Analytics for 30 s while a 10-station
   load runs. **Expected**: zero checkout errors attributable to Analytics, and all scans counted
   after recovery (SC-010).
5. **Snapshot atomicity**: concurrent `GET /analytics/popular-items` during publication never
   returns a ranking that mixes two `windowEnd`s (US4-5).
6. **Empty**: on a fresh reset, popular items returns `items: []` with `windowStart=0`,
   `windowEnd=0` (US4-6).

## V9: Settled reconciliation (FR-019, SC-005)

```bash
domain-services/deployment/scripts/reconcile.sh --wait-settled 60
```

The script reads each service's `/internal/v1/ops/...` export (never another database) and
evaluates checks R-1…R-6 from [data-model.md](data-model.md#settled-state-reconciliation-fr-019-sc-005).

**Expected**: `RECONCILIATION PASS` for every SKU, with zero unresolved attempts, zero `RESERVED`
reservations, an equal set of committed reservations and receipt attempt ids, and outbox total
equal to ingested count. The output is saved beside the run's report.

## V10: Baseline and redesign performance (US5, Gate 4)

**Baseline (before any redesign code)**:

1. Check out tag `baseline/service-based-v1` in a separate worktree. This is the current code
   plus the instrumentation-only commit.
2. Start the old Compose topology and apply `provisioning/measured-stock-baseline.sql`.
3. Warm up for 30 s.
4. Run three normal and three stress runs, resetting before each:

```bash
load-client/run.sh --stations=10 --duration=60 --reportDir=domain-services/deployment/reports/baseline
```

```bash
load-client/run.sh --stations=200 --duration=180 --reportDir=domain-services/deployment/reports/baseline
```

5. During each run, capture observations:

```bash
domain-services/deployment/scripts/capture-observations.sh --out domain-services/deployment/reports/baseline
```

6. Run the baseline's reconciliation SQL after each run.

**Redesign**: the same procedure on the redesigned topology with
`provisioning/measured-stock.sql`, with reports in `reports/redesign/` and V9 after each run.

**Comparison**: `domain-services/deployment/scripts/compare-reports.sh` writes `reports/COMPARISON.md`.

**Expected**:
- Normal runs have zero errors (SC-008).
- Stress runs record every success and failure.
- `COMPARISON.md` shows the median stress scan p95 (redesign ≤ 0.90 × baseline), successful scan
  throughput (redesign ≥ baseline) and median start and complete p95 (redesign ≤ baseline).
- It lists host, topology, pool, seed and timeout differences, and states each SC-009 target
  as **met** or **unmet**. Failed requests are never excluded.
- The 2026-10-16 reports are under `reports/historical/` and are labeled as not baseline
  evidence.

## V11: Documentation entry point (Gate 7)

Open `domain-services/deployment/README.md`. **Expected**: a reviewer can reach each of the
following in under 5 minutes (SC-011):
- deployments and ports;
- the ownership and permission table;
- internal contracts (links into this feature's `contracts/`);
- the saga and recovery rules;
- the analytics flow;
- baseline and redesign reports with `COMPARISON.md`;
- reconciliation outputs.

`ARCHITECTURE.md` describes the private-store design, and superseded documents carry a
pointer to [public-api-amendment.md](contracts/public-api-amendment.md).
