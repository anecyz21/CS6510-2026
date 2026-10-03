---

description: "Dependency-ordered tasks for partial checkout in the layered self-checkout server"
---

# Tasks: Layered Server Architecture — Partial Checkout

**Input**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/http-api.md](contracts/http-api.md), and
[quickstart.md](quickstart.md)

**Tests**: No test-first workflow was requested. Validation tasks use the documented acceptance
scenarios, supplied load client, and existing inventory-invariant check.

**Organization**: Tasks are grouped by user story after the shared partial-fulfillment foundation.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm the existing standalone server can be changed and exercised without changing
the shared contract or load harness.

- [X] T001 Verify the build, launch, and small-catalog invocation paths in `layered-server/build.sh`, `layered-server/run.sh`, and `specs/002-layered-architecture/quickstart.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Create the domain and data-access primitives needed to atomically fulfill only the
available portion of a basket. Complete this phase before user-story work.

- [X] T002 [P] Add an unavailable-item domain value with `sku: Non-empty and unique`, `name: Non-empty`, and a positive retained quantity in `layered-server/src/selfcheckout/domain/UnavailableItem.java`
- [X] T003 [P] Add a completion-result domain value containing the documented receipt and optional unavailable-item details in `layered-server/src/selfcheckout/domain/CompletionResult.java`
- [X] T004 Update pending-basket mutation in `layered-server/src/selfcheckout/domain/Transaction.java` so lines `May grow only while open` and fulfilled units can be removed while unavailable units remain
- [X] T005 Replace the all-or-nothing inventory mutation contract with an atomic per-SKU fulfillment result in `layered-server/src/selfcheckout/dataaccess/SelfCheckoutStore.java`; preserve `quantity: Integer greater than or equal to zero`
- [X] T006 Implement the atomic fulfillment operation under the inventory lock in `layered-server/src/selfcheckout/dataaccess/InMemorySelfCheckoutStore.java`, returning fulfilled and retained quantities without importing HTTP types
- [X] T007 Update the concurrent invariant exercise in `layered-server/test/selfcheckout/integration/InventoryInvariantCheck.java` to validate that atomic fulfillment never drives stock below zero

**Checkpoint**: Domain values and data access can atomically sell available units and report the
retained shortage for every SKU.

---

## Phase 3: User Story 1 - Complete Checkout Through Clear Responsibilities (Priority: P1) 🎯 MVP

**Goal**: A customer completes all available scanned units, keeps unavailable units in the open
basket, and receives an explicit unavailable-item notice.

**Independent Test**: With one unit of stock per SKU, exhaust `SKU-000001`, then complete a basket
containing `SKU-000001` and `SKU-000002`; the receipt contains only `SKU-000002`,
`unavailableItems` identifies `SKU-000001`, and status remains `OPEN` with the retained unit.

- [X] T008 [US1] Implement partial-completion orchestration in `layered-server/src/selfcheckout/transactions/TransactionService.java`: fulfill available units, decrement each fulfilled line exactly once, remove only fulfilled units, and retain unavailable units
- [X] T009 [US1] Update transaction lifecycle handling in `layered-server/src/selfcheckout/transactions/TransactionService.java` so a transaction becomes `COMPLETED` only when no pending lines remain; reject only missing, non-open, and empty transactions
- [X] T010 [US1] Encode documented receipt fields plus the optional `unavailableItems` array with `sku`, `name`, and `quantity` in `layered-server/src/selfcheckout/api/SelfCheckoutRoutes.java`, without direct data-access access from the API layer
- [X] T011 [US1] Preserve OpenAPI error translation for missing, invalid-state, and empty-basket cases in `layered-server/src/selfcheckout/api/SelfCheckoutRoutes.java`; do not return a stock-shortage conflict for a non-empty open basket
- [X] T012 [US1] Execute the normal and all-unavailable partial-completion acceptance scenarios in `specs/002-layered-architecture/quickstart.md` and record the observed payloads in `layered-server/README.md`

**Checkpoint**: A mixed-stock basket produces a successful, receipt-compatible partial checkout,
and a retry cannot charge an already fulfilled unit twice.

---

## Phase 4: User Story 2 - View Operational Insights (Priority: P2)

**Goal**: Operators continue to receive correct low-stock and popularity information after partial
checkout operations.

**Independent Test**: Perform a partial completion, then retrieve low-stock and popular-items
results; fulfilled units affect stock and scans remain in the configured hopping window.

- [X] T013 [US2] Verify and adjust low-stock lookup in `layered-server/src/selfcheckout/dataaccess/InMemorySelfCheckoutStore.java` so it reflects only quantities decremented by partial fulfillment
- [X] T014 [US2] Preserve successful-scan-only recording and hopping-window ranking behavior in `layered-server/src/selfcheckout/analytics/AnalyticsService.java` while partial completion changes transaction state
- [X] T015 [US2] Validate `GET /inventory/low-stock` and `GET /analytics/popular-items` after a partial checkout through `layered-server/src/selfcheckout/api/SelfCheckoutRoutes.java`, recording results in `layered-server/README.md`

**Checkpoint**: Partial checkout does not give analytics transaction-state ownership or corrupt
low-stock alerts.

---

## Phase 5: User Story 3 - Review Layer Boundaries (Priority: P3)

**Goal**: A reviewer can trace partial checkout through API, transaction, data-access, and
analytics responsibilities without forbidden dependencies.

**Independent Test**: Trace completion from `SelfCheckoutRoutes` through `TransactionService` to
`SelfCheckoutStore`, and confirm no API source imports the data-access package.

- [X] T016 [US3] Update the partial-checkout request trace and ownership rules in `layered-server/ARCHITECTURE.md` to match `specs/002-layered-architecture/contracts/http-api.md`
- [X] T017 [US3] Review imports and dependency direction in `layered-server/src/selfcheckout/api/SelfCheckoutRoutes.java`, `layered-server/src/selfcheckout/transactions/TransactionService.java`, `layered-server/src/selfcheckout/analytics/AnalyticsService.java`, and `layered-server/src/selfcheckout/dataaccess/InMemorySelfCheckoutStore.java`; record the result in `layered-server/ARCHITECTURE.md`

**Checkpoint**: The four layers have reviewable, non-overlapping ownership for partial checkout.

---

## Phase 6: Polish & Cross-Cutting Validation

**Purpose**: Prove contract conformance, inventory correctness, and performance evidence under the
constitution's quality gates.

- [X] T018 Compile and launch the implementation through `layered-server/build.sh` and `layered-server/run.sh`, resolving only server-project build failures
- [X] T019 Run all seven endpoint smoke checks and partial-completion scenarios from `specs/002-layered-architecture/quickstart.md` against `spec/self-checkout-openapi.yaml`; record outcomes in `layered-server/README.md`
- [X] T020 Run the normal 10-station, 60-second workload without changing `load-client/`, then save its timestamped JSON report under `layered-server/reports/`
- [X] T021 Run the higher-concurrency stress workload without changing `load-client/`; verify each SKU's final stock equals initial stock minus fulfilled line items and no stock is negative, then save its timestamped JSON report under `layered-server/reports/`
- [X] T022 Record mean, p95, p99, throughput, error rate, and popular-ranking comparison notes for normal and stress runs in `layered-server/reports/README.md`, including an explanation of any stress errors

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1** has no dependencies.
- **Phase 2** depends on Phase 1 and blocks every user story.
- **US1 (Phase 3)** depends on Phase 2 and is the MVP.
- **US2 (Phase 4)** depends on the partial-fulfillment behavior from US1.
- **US3 (Phase 5)** depends on the completed implementation from US1 and US2.
- **Polish (Phase 6)** depends on the desired user stories.

### User Story Dependencies

```text
Setup → Foundational → US1 (MVP) → US2 → US3 → Polish
```

### Parallel Opportunities

- T002 and T003 can proceed in parallel after T001.
- T004 and T005 can begin independently, but T006 requires the finalized store interface from T005.
- T013 and T014 can proceed in parallel after US1 completes.
- T016 can proceed in parallel with the dependency review in T017 once the implementation is stable.
- T020 and preparation of the evidence notes for T022 can proceed in parallel after T018; T021
  requires a fresh server state or a documented reset.

## Parallel Example: Foundational Work

```text
Task: "Add UnavailableItem in layered-server/src/selfcheckout/domain/UnavailableItem.java"
Task: "Add CompletionResult in layered-server/src/selfcheckout/domain/CompletionResult.java"
```

## Implementation Strategy

### MVP First

1. Complete T001–T007 to establish safe partial fulfillment.
2. Complete T008–T012 for the customer-facing partial checkout.
3. Stop and validate the mixed-stock and all-unavailable scenarios before implementing insights.

### Incremental Delivery

1. Add US2 to confirm partial fulfillment preserves operational reporting.
2. Add US3 to document and review the architectural boundaries.
3. Complete normal and stress validation and commit the evidence reports.

## Notes

- Every task follows the required checkbox, ID, optional parallel marker, story label, and file-path format.
- The OpenAPI contract, mock server, and load client remain unchanged.

## Phase 7: Convergence

- [X] T023 CRITICAL Extend `layered-server/test/selfcheckout/integration/InventoryInvariantCheck.java` to stress concurrent transaction completions with mixed and duplicate-SKU baskets, then assert per-SKU fulfilled counts equal initial stock minus final stock and no retained or fulfilled path makes stock negative per Constitution IV and SC-003 (partial)
- [X] T024 CRITICAL Commit `layered-server/reports/report-20261003-030757.json`, `layered-server/reports/report-20261003-031103.json`, and `layered-server/reports/README.md` alongside the implementation evidence per Constitution V (contradicts)
