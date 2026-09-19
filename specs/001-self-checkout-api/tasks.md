---

description: "Task list for the Self-Checkout System API — Monolith Week"
---

# Tasks: Self-Checkout System API — Monolith Week

**Input**: Design documents from `/specs/001-self-checkout-api/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/README.md),
[quickstart.md](./quickstart.md)

**Tests**: INCLUDED. Not optional here — Constitution Gate 1 requires contract tests for every
documented outcome, Gate 4 requires the inventory invariant be verified *under load* rather
than inferred, and SC-005/SC-007 are test-shaped acceptance criteria. Test strategy comes from
research.md R14.

**Organization**: Tasks are grouped by user story so each is independently implementable and
testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story the task belongs to (US1–US5)
- Exact file paths are given in every task

## Path Conventions

Per plan.md's Structure Decision, this week's implementation is a self-contained Maven project
at `monolith/`. Java sources live under
`monolith/src/main/java/edu/northeastern/cs6510/selfcheckout/` — abbreviated **`SRC/`** below —
and tests under `monolith/src/test/java/edu/northeastern/cs6510/selfcheckout/`, abbreviated
**`TEST/`**.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project scaffolding that lets the harness reach a running service

- [X] T001 Create the `monolith/` directory tree per plan.md: `src/main/java/edu/northeastern/cs6510/selfcheckout/{api,catalog,inventory,transaction,analytics,config}`, `src/main/resources/db/migration`, and `src/test/java/edu/northeastern/cs6510/selfcheckout/{contract,concurrency,unit}`
- [X] T002 Create `monolith/pom.xml` targeting Java 21 with Spring Boot 3.x and the `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-jdbc`, and `spring-boot-starter-test` starters; add MySQL Connector/J and Flyway; pin exact dependency versions. Add the Maven wrapper (`monolith/mvnw`, `monolith/mvnw.cmd`, `monolith/.mvn/`) so no global Maven is needed (R13)
- [X] T003 [P] Create `monolith/build.sh` (runs `./mvnw -q package`) and `monolith/run.sh` (runs the packaged jar), matching the script convention in `load-client/` and `mockserver/` (R13)
- [X] T004 [P] Create `monolith/src/main/resources/application.yaml` with `server.port: 8080`, externally configurable MySQL datasource settings (URL, username, password), Flyway enabled, and defaults `catalogSize: 2000`, `stockPerItem: 10000`, `lowStockThreshold: 50`, `windowSize: 1000`, `slideInterval: 500`; leave `spring.threads.virtual.enabled` absent so platform threads are used (R12)
- [X] T004a [P] Create initial Flyway migrations under `monolith/src/main/resources/db/migration/` for catalog items, inventory, transactions, transaction lines, claims, scan events, popularity snapshots, and inventory movements. Include primary keys, foreign keys, and indexes needed by the contract queries; deterministic catalog and starting inventory data are populated by T013's idempotent startup seed.
- [X] T005 [P] Create `monolith/reports/.gitkeep` so Gate 5 has a committed home for run reports (Constitution Principle V)
- [X] T006 Create `SRC/SelfCheckoutApplication.java` as the Spring Boot entry point

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Configuration, error mapping, money handling, and the catalog and inventory state
that every user story reads or mutates

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T007 Create `SRC/config/SelfCheckoutProperties.java` binding `catalogSize`, `stockPerItem`, `lowStockThreshold`, `windowSize`, and `slideInterval` from `application.yaml`
- [X] T008 Add startup validation to `SRC/config/SelfCheckoutProperties.java` enforcing FR-027 verbatim: `windowSize >= 1` and `1 <= slideInterval <= windowSize`. A violating combination MUST fail startup rather than be silently corrected (R9)
- [X] T009 [P] Create `SRC/api/MoneySerialization.java` converting `long` cents to a JSON number with exactly two decimal places. Domain code keeps money as `long` cents throughout; only serialization converts (FR-030, R7)
- [X] T010 [P] Create `SRC/api/ApiErrorResponse.java` with the two required fields `error` (machine-readable) and `message` (human-readable) per the contract's `ApiError` schema (FR-029)
- [X] T011 Create `SRC/api/GlobalExceptionHandler.java` as a `@ControllerAdvice` mapping domain failures to the code catalog in contracts/error-codes.md: `MISSING_STATION_ID` → 400, `INVALID_LIMIT` → 400, `TRANSACTION_NOT_FOUND` → 404, `SKU_NOT_FOUND` → 404, `TRANSACTION_NOT_OPEN` → 409, `EMPTY_BASKET` → 409. `INSUFFICIENT_STOCK` is added in T039
- [X] T012 [P] Create `SRC/catalog/CatalogItem.java` as an immutable record of `sku` (`String`), `name` (`String`), `priceCents` (`long`). Constraints from data-model.md: `sku` unique across the catalog and non-blank; `priceCents >= 0`
- [X] T013 Create `SRC/catalog/CatalogSeeder.java` to populate MySQL with `catalogSize` items when the catalog is empty: SKUs formatted `SKU-%06d` from 1, names derived deterministically from the index, and prices from a fixed-seed generator. The same configuration MUST produce a byte-identical catalog in every environment and week (R11, SC-008)
- [X] T014 Create `SRC/inventory/SkuInventory.java` mapped to the inventory table, holding `stock` (`int`), `reserved` (`int`), and `lowStockSince` (`Instant`, nullable). Document and enforce the core invariant verbatim: `stock >= reserved >= 0`.
- [X] T015 Create `SRC/inventory/InventoryStore.java` as the JDBC-backed inventory store, exposing the existing claim and release primitives against MySQL rather than a `ConcurrentHashMap` (FR-016, FR-017, R4)
- [X] T016 [P] Create `SRC/transaction/Transaction.java` with `transactionId`, `stationId`, `status` (enum `OPEN | COMPLETED | CANCELLED`), `scannedQty` (`Map<String,Integer>`), `claimedQty` (`Map<String,Integer>`), `itemCount`, `runningTotalCents`, `startedAt`, `completedAt` (nullable). Enforce `claimedQty[sku] <= scannedQty[sku]` for every SKU (data-model.md)
- [X] T017 Create `SRC/transaction/TransactionStore.java` as a JDBC-backed store for transactions, lines, and claims with unique server-side id generation (FR-003)

**Checkpoint**: Configuration, errors, money, catalog, and inventory state exist — user stories
can begin

---

## Phase 3: User Story 1 - Complete a purchase at a station (Priority: P1) 🎯 MVP

**Goal**: A station can read the catalog, open a transaction, scan units, pay, and receive a
receipt, with stock decremented to match what left the building.

**Independent Test**: fetch the catalog, open a transaction, scan several known SKUs, complete
it, then confirm the receipt lines and total match the scans and each SKU's stock fell by
exactly the units scanned. Delivers a working checkout with no other story implemented.

### Tests for User Story 1

> Write these first and confirm they fail before implementing.

- [X] T018 [P] [US1] Contract test for `GET /items` in `TEST/contract/CatalogContractTest.java`: 200 with every seeded item carrying `sku`, `name`, `price`; two calls in one run return identical membership and prices (FR-001, FR-002)
- [X] T019 [P] [US1] Contract test for `POST /transactions` in `TEST/contract/StartTransactionContractTest.java`: 201 with status `OPEN`, `itemCount` 0, `runningTotal` 0, `startedAt` set; and 400 `MISSING_STATION_ID` when `stationId` is absent or blank (FR-003, FR-004)
- [X] T020 [P] [US1] Contract test for `POST /transactions/{id}/items` in `TEST/contract/ScanItemContractTest.java`: 200 with `itemCount` +1 and `runningTotal` + unit price; 404 `TRANSACTION_NOT_FOUND`; 404 `SKU_NOT_FOUND`; 409 `TRANSACTION_NOT_OPEN` (FR-005, FR-006, FR-008, FR-009)
- [X] T021 [P] [US1] Contract test for `POST /transactions/{id}/complete` in `TEST/contract/CompleteTransactionContractTest.java`: 200 with a receipt whose lines aggregate one per distinct SKU; 404 `TRANSACTION_NOT_FOUND`; 409 `EMPTY_BASKET`; 409 `TRANSACTION_NOT_OPEN` on a second attempt with stock unchanged (FR-011, FR-012)
- [X] T022 [P] [US1] Unit test in `TEST/unit/ReceiptAggregationTest.java`: three scans of one SKU produce a single line with `quantity: 3`; `sum(line.unitPriceCents * line.quantity) == totalAmountCents` exactly; `sum(line.quantity) == itemCount` (FR-011, FR-030, SC-006)

### Implementation for User Story 1

- [X] T023 [P] [US1] Create `SRC/catalog/CatalogService.java` exposing the full item list and single-SKU lookup over the seeded catalog
- [X] T024 [US1] Create `SRC/api/CatalogController.java` serving `GET /items` as a `CatalogResponse` with an `items` array (FR-001)
- [X] T025 [P] [US1] Create `SRC/api/dto/` request and response DTOs mirroring the contract schemas: `StartTransactionRequest` (`stationId` required), `ScanItemRequest` (`sku` required), `TransactionResponse`, `ScanResultResponse`, `ReceiptResponse`, `ReceiptLineResponse`
- [X] T026 [US1] Create `SRC/transaction/TransactionService.java` with `start(stationId)`: reject blank `stationId`, otherwise create an `OPEN` transaction with zero counts and a `startedAt` timestamp (FR-003, FR-004)
- [X] T027 [US1] Add `scan(transactionId, sku)` to `SRC/transaction/TransactionService.java`: reject unknown transaction or SKU as not-found and a non-open transaction as conflict; otherwise record exactly one unit in `scannedQty`, attempt a claim via `InventoryStore` and record it in `claimedQty` when granted, and update `itemCount` and `runningTotalCents`. A scan MUST succeed regardless of stock and regardless of whether a claim was obtained (FR-005, FR-006, FR-007, FR-017)
- [X] T028 [P] [US1] Create `SRC/transaction/Receipt.java` and `SRC/transaction/ReceiptLine.java` aggregating `scannedQty` into one line per distinct SKU with `sku`, `name`, `unitPriceCents`, `quantity` (FR-011)
- [X] T029 [US1] Add the single-step decrement to `SRC/inventory/InventoryStore.java`: for each SKU apply `stock -= scannedQty[sku]` and `reserved -= claimedQty[sku]` atomically per SKU. Multi-SKU ordering is added in T036
- [X] T030 [US1] Add `complete(transactionId)` to `SRC/transaction/TransactionService.java`: reject unknown as not-found, and non-open or empty basket as conflict; verify the transaction-local feasibility condition `claimedQty[sku] == scannedQty[sku]` for every SKU; then decrement stock, release claims, set `status = COMPLETED` and `completedAt`, and return the receipt. Payment always succeeds (FR-010, FR-011, FR-012, FR-017, R5)
- [X] T031 [US1] Create `SRC/api/TransactionController.java` serving `POST /transactions` (201), `POST /transactions/{id}/items` (200), and `POST /transactions/{id}/complete` (200)

**Checkpoint**: A full purchase works end to end and T018–T022 pass. Gate 1 is partially
satisfied; the harness can complete transactions against plentiful stock.

---

## Phase 4: User Story 2 - Keep inventory accurate under concurrency (Priority: P2)

**Goal**: Counts reconcile under concurrent load — nothing sold twice, nothing lost, nothing
negative — and a transaction that cannot be fulfilled is refused rather than oversold.

**Independent Test**: run many concurrent stations to completion against known starting stock,
then reconcile per SKU: starting minus ending stock equals the quantity across successfully
completed transactions, with no SKU negative.

### Tests for User Story 2

- [ ] T032 [P] [US2] Concurrency test in `TEST/concurrency/OversubscribedSkuTest.java`: with a SKU seeded to N units and far more than N transactions each scanning one unit, exactly N transactions complete, every other is refused 409 `INSUFFICIENT_STOCK` and left `CANCELLED`, final stock is exactly 0 and never negative, and no refused transaction leaves a receipt or a stock change (SC-005)
- [ ] T033 [P] [US2] Reconciliation test in `TEST/concurrency/ReconciliationTest.java`: drive bulk concurrent transactions across many SKUs, then assert for every SKU that `initialStock - finalStock == total quantity across COMPLETED transactions` and `finalStock >= 0`, with `CANCELLED` and still-open transactions contributing to neither side (FR-018, SC-004)
- [X] T034 [P] [US2] Unit test in `TEST/unit/ClaimAccountingTest.java`: concurrent claim attempts never drive `reserved` above `stock`; claim conservation holds — for every SKU, `reserved` equals the sum of `claimedQty[sku]` across all `OPEN` transactions; a release happens exactly once per claim (data-model.md cross-entity invariants 1 and 5)
- [X] T035 [P] [US2] Contract test in `TEST/contract/InsufficientStockContractTest.java`: completing a transaction holding an unclaimed unit returns 409 `INSUFFICIENT_STOCK` with no receipt and no stock change; a following `GET /transactions/{id}` shows status `CANCELLED`; a second completion attempt returns 409 `TRANSACTION_NOT_OPEN` (FR-013, R6)

### Implementation for User Story 2

- [X] T036 [US2] Replace the per-SKU decrement in `SRC/inventory/InventoryStore.java` with an ordered multi-SKU apply: sort the basket's distinct SKUs by SKU string, acquire every lock in that order, apply all decrements, then release. Sorted acquisition is what prevents deadlock; the locks exist to make the basket's decrement atomic for readers, not to arbitrate between completions (FR-010, FR-016, R5)
- [X] T037 [US2] Add insufficient-stock detection to `complete()` in `SRC/transaction/TransactionService.java`: when any SKU has `claimedQty[sku] < scannedQty[sku]`, refuse with the insufficient-stock failure, issue no receipt, change no stock, and do not mark the transaction completed (FR-013)
- [X] T038 [US2] Add `cancel(transactionId)` to `SRC/transaction/TransactionService.java` setting `status = CANCELLED` and releasing every claim the transaction holds, and invoke it from the insufficient-stock refusal path. The refusal is terminal: claims are taken only at scan time, so such a transaction can never complete (FR-017, R6)
- [X] T039 [US2] Extend `SRC/api/GlobalExceptionHandler.java` to map the insufficient-stock failure to 409 `INSUFFICIENT_STOCK`, distinct and stable against the other two conflict codes (FR-013, FR-029)

**Checkpoint**: US1 and US2 both hold. Gate 4's fast half passes; the design is ready for the
stress run.

---

## Phase 5: User Story 3 - See which items are running low (Priority: P3)

**Goal**: An operator can identify every item needing restock in one query.

**Independent Test**: complete enough transactions to push known SKUs below the threshold, query
low stock, and confirm exactly those SKUs are reported with their current counts.

### Tests for User Story 3

- [X] T040 [P] [US3] Contract test in `TEST/contract/LowStockContractTest.java`: 200 with `threshold`, `generatedAt`, and alerts for every SKU **strictly below** threshold; a SKU whose stock **equals** the threshold is NOT reported; `?threshold=0` returns an empty `alerts` array because stock is never negative; `?threshold=N` reflects N and leaves the configured default unchanged for later queries; nothing below threshold returns an empty array rather than an error (FR-019, FR-020, US3 §3, §4)
- [X] T041 [P] [US3] Contract test addition in the same file: while units are claimed by open transactions but unsold, `currentStock` reports physical stock on hand — a claim is not a stock movement (FR-021, US3 §5)

### Implementation for User Story 3

- [X] T042 [US3] Add `lowStockSince` maintenance to the decrement path in `SRC/inventory/SkuInventory.java`: stamp it when a decrement takes stock **strictly below** the configured default threshold, and clear it if stock ever rises to or above that threshold. Equality with the threshold is NOT a crossing (FR-019, R10)
- [X] T043 [US3] Create `SRC/inventory/LowStockService.java` returning every SKU where `stock < threshold`, using the query-supplied threshold when present and the configured default otherwise. Resolve `triggeredAt` from persisted inventory-movement history as the latest transition below that query's threshold; report physical `stock` and never substitute report-generation time for an unknown crossing (FR-019, FR-020, FR-021)
- [X] T044 [US3] Create `SRC/api/InventoryController.java` serving `GET /inventory/low-stock` with the optional `threshold` query parameter, plus `LowStockResponse` and `LowStockAlertResponse` DTOs carrying `threshold`, `generatedAt`, and `alerts[]` of `sku`, `name`, `currentStock`, `threshold`, `triggeredAt`

**Checkpoint**: US1–US3 hold independently

---

## Phase 6: User Story 4 - See which items are selling right now (Priority: P4)

**Goal**: A reproducible ranking of the most-scanned items over a hopping window.

**Independent Test**: drive a deliberately skewed mix of scans, query popular items, and confirm
the ranking matches the mix and the reported window bounds match the scans recorded.

### Tests for User Story 4

- [ ] T045 [P] [US4] Contract test in `TEST/contract/PopularItemsContractTest.java`: 200 reporting `windowSize`, `slideInterval`, `windowStart`, `windowEnd`, `computedAt`, and `items` ranked by `scanCount` descending with `rank` from 1; `?limit=N` returns at most N; `?limit=0` or negative is refused 400 `INVALID_LIMIT`; a query before the first boundary returns an empty ranking with `windowStart = windowEnd = 0`; two queries between boundaries are byte-identical (FR-023, FR-024, FR-025, FR-027)
- [ ] T046 [P] [US4] Unit test in `TEST/unit/PopularityWindowTest.java`: boundaries fall at sequence position `windowSize`, then every `slideInterval` thereafter — 1000, 1500, 2000 for the defaults, NOT at every multiple of `slideInterval`; `windowStart == windowEnd - windowSize + 1`; a query at a position between boundaries yields the most recently completed boundary's snapshot; ties break stably by SKU (FR-023, FR-024, R8)
- [ ] T047 [P] [US4] Unit test in `TEST/unit/ScanSequenceTest.java`: sequence numbers are unique and strictly increasing under concurrent scans, and ordering follows sequence rather than wall-clock timestamps (FR-022)

### Implementation for User Story 4

- [ ] T048 [P] [US4] Create `SRC/analytics/ScanSequence.java` wrapping an `AtomicLong` whose increment is the linearization point at which a scan is accepted, yielding unique, strictly increasing numbers (FR-022, R8)
- [ ] T049 [P] [US4] Create `SRC/analytics/ScanRingBuffer.java` of `windowSize` slots, writing each scan's SKU at index `(seq - 1) % windowSize` (R8). Persist every accepted scan event to MySQL so analytics state survives restart and remains auditable.
- [ ] T050 [US4] Create `SRC/analytics/PopularityWindow.java` as an immutable snapshot (`windowSize`, `slideInterval`, `windowStart`, `windowEnd`, `computedAt`, ranked `items`) held in a single `volatile` reference, republished by the scan whose sequence number lands on a boundary. Publish an empty snapshot with both bounds zero at startup (FR-023, FR-024, R8)
- [ ] T051 [US4] Wire sequence assignment and ring-buffer write into `scan()` in `SRC/transaction/TransactionService.java`. A scan counts toward popularity when it is scanned, whether or not its transaction later completes, is cancelled, or is refused (FR-026)
- [ ] T052 [US4] Create `SRC/api/AnalyticsController.java` serving `GET /analytics/popular-items` with an optional `limit` (default 10, validated `limit >= 1`), returning the current snapshot without recomputing (FR-025, FR-027)

**Checkpoint**: US1–US4 hold independently

---

## Phase 7: User Story 5 - Inspect a transaction while debugging (Priority: P5)

**Goal**: One transaction's current state is readable by identifier.

**Independent Test**: open a transaction, scan into it, fetch it by identifier, and confirm the
status, unit count, and running total match the scans.

### Tests for User Story 5

- [X] T053 [P] [US5] Contract test in `TEST/contract/GetTransactionContractTest.java`: 200 returning `transactionId`, `stationId`, `status`, `itemCount`, `runningTotal`, `startedAt`; 404 `TRANSACTION_NOT_FOUND` for an unknown id, rather than an empty or fabricated transaction (FR-014)

### Implementation for User Story 5

- [X] T054 [US5] Add `GET /transactions/{id}` to `SRC/api/TransactionController.java` returning the current transaction state, reusing the `TransactionResponse` DTO from T025 (FR-014)

**Checkpoint**: all five stories hold independently; every contract operation is implemented

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Close the constitution's five gates and leave the week reproducible

- [X] T055 [P] Create `monolith/README.md` documenting build, run, configuration keys, and the architecture style, noting that framework overhead and platform-thread defaults are part of the measurements (R2, R12)
- [ ] T056 Run the full contract suite — `cd monolith && ./mvnw test` — and confirm every row of contracts/README.md's conformance matrix is covered and green. **Gate 1**
- [ ] T057 Run the normal load-client run per quickstart.md against a freshly started service and confirm zero errors on `START_TRANSACTION`, `SCAN_ITEM`, and `COMPLETE_TRANSACTION`, including zero `INSUFFICIENT_STOCK`. **Gate 2** (SC-002)
- [ ] T058 Run the stress run per quickstart.md and confirm it completes and writes a report; record p95 and p99 per operation and explain any non-zero error rate. **Gate 3** (SC-003, SC-009)
- [ ] T059 Reconcile the stress run's final state per SKU with a repeatable MySQL verification query or internal command (not a public API): compare recorded initial inventory, final inventory, and completed transaction-line quantities; assert `initialStock - finalStock == total quantity across COMPLETED transactions`, `finalStock >= 0`, and claim conservation while open transactions remain. Save the verification result alongside the run report before restarting the service. **Gate 4** (SC-004, FR-018)
- [ ] T060 Copy both JSON reports into `monolith/reports/` and commit them with notes naming the architecture style, stating that no previous week exists for comparison, and recording the framework-overhead and platform-thread caveats. **Gate 5** (Constitution Principle V)
- [ ] T061 [P] Code cleanup pass: confirm only `SRC/inventory/` mutates stock, that `SRC/api/` is the only package aware of HTTP, and that no package introduces layering or indirection reserved for the layered week (plan.md Structure Decision)
- [ ] T062 Verify no API surface beyond the contract exists — no reconciliation or debug endpoint, no claim state in any payload or error message (FR-031, FR-032)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup — BLOCKS all user stories
- **User Stories (Phases 3–7)**: all depend on Foundational
- **Polish (Phase 8)**: depends on every story being complete, since the gates exercise the
  whole contract

### User Story Dependencies

- **US1 (P1)**: depends only on Foundational. The MVP.
- **US2 (P2)**: depends on Foundational, and **hardens US1's completion path** — T036 replaces
  T029 and T037 extends T030. Not independent of US1 in code, though independently *testable*:
  T032–T035 verify behavior US1's tests do not touch.
- **US3 (P3)**: depends on Foundational. T042 touches the inventory decrement path US1/US2
  own, so it cannot run in parallel with T029 or T036.
- **US4 (P4)**: depends on Foundational. T051 touches `scan()` from US1's T027, so it cannot
  run in parallel with it.
- **US5 (P5)**: depends on Foundational and on T025's DTO. Fully independent otherwise, and the
  cheapest story to finish.

### Within Each User Story

- Tests are written first and must fail before implementation
- Entities before services; services before controllers
- Story complete before moving to the next priority

### Parallel Opportunities

- **Phase 1**: T003, T004, T005 in parallel after T001 and T002
- **Phase 2**: T009, T010, T012, T016 in parallel; T011 waits on T010; T013 waits on T012;
  T015 waits on T014
- **US1**: T018–T022 all in parallel; then T023, T025, T028 in parallel
- **US2**: T032–T035 all in parallel; implementation T036–T039 is sequential on shared files
- **US3**: T040 and T041 in parallel
- **US4**: T045–T047 in parallel; then T048 and T049 in parallel
- **Cross-story**: US5 can be done by a second person at any point after T025 without touching
  US1–US4 code

---

## Parallel Example: User Story 1

```bash
# Launch all US1 tests together (they must fail first):
Task: "Contract test for GET /items in TEST/contract/CatalogContractTest.java"
Task: "Contract test for POST /transactions in TEST/contract/StartTransactionContractTest.java"
Task: "Contract test for POST /transactions/{id}/items in TEST/contract/ScanItemContractTest.java"
Task: "Contract test for POST /transactions/{id}/complete in TEST/contract/CompleteTransactionContractTest.java"
Task: "Unit test for receipt aggregation in TEST/unit/ReceiptAggregationTest.java"

# Then launch the independent US1 pieces together:
Task: "Create CatalogService in SRC/catalog/CatalogService.java"
Task: "Create request/response DTOs in SRC/api/dto/"
Task: "Create Receipt and ReceiptLine in SRC/transaction/"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup
2. Phase 2 Foundational — blocks everything
3. Phase 3 US1
4. **STOP and VALIDATE**: a station completes a purchase; stock matches the receipt
5. At this point the harness runs end to end against plentiful stock, though correctness under
   concurrency is not yet guaranteed — that is US2's job

### Incremental Delivery

1. Setup + Foundational → service boots, catalog seeded
2. + US1 → checkout works (**MVP**)
3. + US2 → survives concurrency; the graded invariant holds
4. + US3 → low-stock visibility
5. + US4 → analytics, and with it SC-008's cross-week cross-check
6. + US5 → debugging aid; contract fully covered
7. Phase 8 → gates closed, reports committed

**Do not stop before US2.** US1 alone passes functional tests while still overselling under
load — precisely the failure Constitution Principle IV exists to catch, and Gate 4 will fail.

### Parallel Team Strategy

Setup and Foundational are shared. Afterwards the cleanest split is one person on US1 then US2
— they share the completion path and are painful to divide — while a second takes US5, then
US3, then US4, coordinating only on the two known touch points: `scan()` (T027 vs T051) and the
inventory decrement (T029/T036 vs T042).

---

## Notes

- `[P]` tasks touch different files and have no incomplete dependencies
- `[Story]` labels map each task to a spec.md user story for traceability
- Verify tests fail before implementing
- Commit after each task or logical group
- Any checkpoint is a safe place to stop and validate
- Requirement references (FR-xxx, SC-xxx) point at [spec.md](./spec.md); decision references
  (R1–R14) point at [research.md](./research.md)

## Phase 9: Convergence

- [ ] T063 Complete durable hopping-window analytics: persist each accepted scan and published snapshot in MySQL, recover the latest snapshot on restart, preserve the sequence linearization point, and implement the missing US4 contract and unit tests (FR-022, FR-023, FR-024, FR-025, FR-026, FR-027; partial)
- [ ] T064 Add a MySQL-backed end-to-end contract suite that runs Flyway and verifies every documented contract outcome without mocking the JDBC stores (Gate 1, FR-001–FR-032; missing)
- [ ] T065 Add the required concurrent oversubscription and per-SKU reconciliation tests, plus a repeatable non-public MySQL reconciliation query/script that verifies stock, completed lines, and open-claim conservation after stress load (FR-016, FR-017, FR-018, SC-004, Constitution IV; missing)
- [ ] T066 Provide documented, reproducible MySQL provisioning and credential configuration for the monolith, and correct the conflicting quickstart statement that no database is required; verify a clean service startup and catalog request with those instructions (plan: MySQL storage decision; partial)
- [ ] T067 Run the unmodified root load client against a freshly seeded MySQL database for the normal and stress workloads; save both timestamped JSON reports under `monolith/reports/`, record p95/p99 and any errors, and save the stress reconciliation result before restarting the service (SC-002, SC-003, SC-004, Constitution V, root README; missing)
