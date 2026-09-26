---

description: "Dependency-ordered implementation tasks for the layered self-checkout server"
---

# Tasks: Layered Server Architecture

**Input**: Design documents from `/specs/002-layered-architecture/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/http-api.md](contracts/http-api.md), and
[quickstart.md](quickstart.md)

**Tests**: No automated test suite was explicitly requested. Validation tasks use the shared
OpenAPI contract, supplied load client, and the quickstart scenarios.

**Organization**: Tasks are grouped by user story so each increment can be implemented and
validated independently after the shared foundation is complete.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the independently runnable Java 21 server layout and build entry points.

- [X] T001 Create `layered-server/src/selfcheckout/{api,transactions,analytics,dataaccess,domain}`, `layered-server/test/selfcheckout/{contract,integration,unit}`, and `layered-server/reports/` per `specs/002-layered-architecture/plan.md`
- [X] T002 Create Java 21 compile and launch scripts in `layered-server/build.sh` and `layered-server/run.sh` with port, catalog-size, stock-per-item, and low-stock-threshold arguments

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Establish shared domain values, data-access boundaries, JSON support, and server
composition. Complete this phase before implementing user stories.

- [X] T003 [P] Create catalog and monetary domain values with constraints `sku: Non-empty and unique`, `name: Non-empty`, and `price: Non-negative monetary value` in `layered-server/src/selfcheckout/domain/CatalogItem.java`
- [X] T004 [P] Create transaction domain values with constraints `transactionId: Unique and non-empty`, `stationId: Non-empty`, `status: OPEN, COMPLETED, or CANCELLED`, and lines that `May grow only while open` in `layered-server/src/selfcheckout/domain/Transaction.java` and `layered-server/src/selfcheckout/domain/TransactionStatus.java`
- [X] T005 [P] Create receipt, receipt-line, API-error, low-stock-alert, and popular-item response values in `layered-server/src/selfcheckout/domain/Receipt.java`, `layered-server/src/selfcheckout/domain/ReceiptLine.java`, `layered-server/src/selfcheckout/domain/ApiError.java`, `layered-server/src/selfcheckout/domain/LowStockAlert.java`, and `layered-server/src/selfcheckout/domain/PopularItem.java`
- [X] T006 Implement the data-access API and in-memory implementation in `layered-server/src/selfcheckout/dataaccess/SelfCheckoutStore.java` and `layered-server/src/selfcheckout/dataaccess/InMemorySelfCheckoutStore.java`; enforce `quantity: Integer greater than or equal to zero` and expose atomic all-or-nothing inventory mutation without HTTP types
- [X] T007 [P] Implement JSON request parsing and response encoding in `layered-server/src/selfcheckout/api/Json.java` and `layered-server/src/selfcheckout/api/ApiResponses.java`, including the contract `ApiError` shape with `error` and `message`
- [X] T008 Create the HTTP server composition root in `layered-server/src/selfcheckout/api/SelfCheckoutServer.java`; instantiate API, transactions, analytics, and data-access collaborators without allowing data access to depend on upper layers

**Checkpoint**: The project compiles, starts, and has the required layer boundaries. User-story work
can begin.

---

## Phase 3: User Story 1 - Complete Checkout Through Clear Responsibilities (Priority: P1) 🎯 MVP

**Goal**: Preserve the complete checkout journey and transaction-status behavior through the API,
transactions, and data-access responsibilities.

**Independent Test**: Start a transaction, scan an existing item, complete it, and retrieve status;
compare the status codes and JSON with `spec/self-checkout-openapi.yaml`.

- [X] T009 [US1] Implement transaction creation, lookup, scan validation, and completion state transitions in `layered-server/src/selfcheckout/transactions/TransactionService.java`; reject missing, non-open, and empty transactions with the documented outcomes
- [X] T010 [US1] Implement atomic completion in `layered-server/src/selfcheckout/transactions/TransactionService.java` using `SelfCheckoutStore` so all basket quantities are verified and decremented exactly once before status becomes `COMPLETED`
- [X] T011 [US1] Implement API routing and response translation for `GET /items`, `POST /transactions`, `POST /transactions/{id}/items`, `POST /transactions/{id}/complete`, and `GET /transactions/{id}` in `layered-server/src/selfcheckout/api/SelfCheckoutRoutes.java`; do not access `SelfCheckoutStore` from this API layer
- [X] T012 [US1] Validate the checkout and transaction-status acceptance scenarios from `specs/002-layered-architecture/spec.md` using the commands in `specs/002-layered-architecture/quickstart.md`, recording any contract mismatches in `layered-server/README.md`

**Checkpoint**: A customer can complete checkout and retrieve transaction status while inventory is
updated only on completion.

---

## Phase 4: User Story 2 - View Operational Insights (Priority: P2)

**Goal**: Deliver low-stock alerts and hopping-window popular-item rankings while checkout remains
correct.

**Independent Test**: Generate scans and a completion, then retrieve `GET /analytics/popular-items`
and `GET /inventory/low-stock`; verify their OpenAPI response shapes and resulting state.

- [X] T013 [US2] Implement monotonic scan recording, bounded window retention, configured slide-interval recomputation, and ranked popularity snapshots in `layered-server/src/selfcheckout/analytics/AnalyticsService.java`
- [X] T014 [US2] Update `layered-server/src/selfcheckout/transactions/TransactionService.java` to notify `AnalyticsService` only after a successful scan, keeping analytics separate from transaction lifecycle ownership
- [X] T015 [US2] Implement low-stock and popular-items response routing in `layered-server/src/selfcheckout/api/SelfCheckoutRoutes.java`, including optional `threshold` and `limit` parsing and no direct data-access calls from API handlers
- [X] T016 [US2] Validate low-stock and popular-items acceptance scenarios and edge cases from `specs/002-layered-architecture/spec.md` using `specs/002-layered-architecture/quickstart.md`, documenting results in `layered-server/README.md`

**Checkpoint**: Operators can retrieve correct low-stock alerts and hopping-window rankings without
analytics owning checkout state transitions.

---

## Phase 5: User Story 3 - Review Layer Boundaries (Priority: P3)

**Goal**: Make the four required responsibilities and their permitted dependencies explicit for a
course evaluator.

**Independent Test**: Trace a checkout request and a popular-items request from route to result and
confirm no API source file imports or invokes the data-access layer directly.

- [X] T017 [US3] Document responsibility ownership and allowed dependency directions in `layered-server/ARCHITECTURE.md`, consistent with `specs/002-layered-architecture/contracts/http-api.md`
- [X] T018 [US3] Review imports and calls in `layered-server/src/selfcheckout/api/SelfCheckoutRoutes.java`, `layered-server/src/selfcheckout/transactions/TransactionService.java`, `layered-server/src/selfcheckout/analytics/AnalyticsService.java`, and `layered-server/src/selfcheckout/dataaccess/InMemorySelfCheckoutStore.java`; record the two request traces in `layered-server/ARCHITECTURE.md`

**Checkpoint**: All four layers have non-overlapping ownership and the dependency rules are reviewable.

---

## Phase 6: Polish & Cross-Cutting Validation

**Purpose**: Complete the constitution's quality gates and preserve comparison evidence.

- [X] T019 Compile and launch the server through `layered-server/build.sh` and `layered-server/run.sh`, resolving only layered-server build failures
- [ ] T020 Run the normal 10-station, 60-second workload exactly as documented in `specs/002-layered-architecture/quickstart.md` and commit its timestamped JSON output under `layered-server/reports/`
- [ ] T021 Run the higher-concurrency stress workload exactly as documented in `specs/002-layered-architecture/quickstart.md`; verify every SKU's final stock equals initial stock minus completed line items and no stock is negative, then commit its timestamped JSON output under `layered-server/reports/`
- [X] T022 Record normal and stress mean, p95, p99, throughput, error-rate, and popular-ranking comparison notes in `layered-server/reports/README.md`, naming the layered architecture style and explaining any stress errors
- [X] T023 Re-run every endpoint and error outcome against `spec/self-checkout-openapi.yaml` and update the validation status in `layered-server/README.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1** has no dependencies.
- **Phase 2** depends on Phase 1 and blocks all user stories.
- **US1 (Phase 3)** depends on Phase 2 and is the MVP.
- **US2 (Phase 4)** depends on Phase 2 and integrates with the scan flow delivered by US1.
- **US3 (Phase 5)** depends on the completed source layout from US1 and US2.
- **Polish (Phase 6)** depends on all user stories.

### User Story Dependencies

```text
Setup → Foundational → US1 (MVP) → US2 → US3 → Polish
```

### Parallel Opportunities

- T003, T004, T005, and T007 can run in parallel after T001 and T002.
- After the foundational data-access API is stable, implementation work for transaction-domain and
  analytics-domain internals can be split, but T014 and T015 must wait for the completed US1 scan
  flow.
- T020 and preparation of the report notes for T022 can proceed in parallel once T019 is complete;
  T021 follows a fresh server state or documented reset.

## Parallel Example: Foundational Work

```text
Task: "Create CatalogItem.java in layered-server/src/selfcheckout/domain/CatalogItem.java"
Task: "Create transaction values in layered-server/src/selfcheckout/domain/Transaction.java and TransactionStatus.java"
Task: "Create response values in layered-server/src/selfcheckout/domain/Receipt.java and related domain files"
Task: "Implement JSON support in layered-server/src/selfcheckout/api/Json.java and ApiResponses.java"
```

## Implementation Strategy

### MVP First

1. Complete setup and the shared foundation.
2. Deliver US1 through T009–T012.
3. Validate checkout and transaction-status behavior against the shared contract before continuing.

### Incremental Delivery

1. Add US2 and validate operational insight endpoints without breaking checkout.
2. Add US3's architecture evidence and dependency review.
3. Run normal and stress workloads, verify inventory, and commit the reports.
