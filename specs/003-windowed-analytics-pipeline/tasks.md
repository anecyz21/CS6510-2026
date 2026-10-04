---

description: "Implementation tasks for the Windowed Analytics Pipeline"
---

# Tasks: Windowed Analytics Pipeline

**Input**: Design documents from `/specs/003-windowed-analytics-pipeline/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [pipeline contract](contracts/pipeline-and-http.md), and
[quickstart.md](quickstart.md)

**Tests**: Required. The specification calls for deterministic window/ranking, concurrent-read,
and orderly-drain verification in addition to the existing inventory-invariant check.

**Organization**: Tasks are grouped by user story so each deliverable can be verified independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can be completed in parallel with other marked tasks once its dependencies are met.
- **[Story]**: Maps the task to a user story from [spec.md](spec.md).
- All paths are repository-relative.

## Phase 1: Setup

**Purpose**: Establish a repeatable way to compile and execute the standalone Java checks.

- [ ] T001 Create `layered-server/test.sh` to compile `layered-server/src/` and
  `layered-server/test/` into isolated output directories and run named standalone test classes.
- [ ] T002 Update `layered-server/README.md` with the pipeline-feature test command and the
  location of the required reports.

---

## Phase 2: Foundational Pipeline Prerequisites

**Purpose**: Define the shared messages and lifecycle boundary required by every pipeline story.

**⚠️ CRITICAL**: Complete this phase before user-story implementation.

- [ ] T003 Create immutable scan, window-update, and terminal message types in `layered-server/src/selfcheckout/analytics/` with
  a positive unique sequence, nonblank valid
  SKU, immutable ordered window events, and terminal-after-prior-work semantics from
  `specs/003-windowed-analytics-pipeline/data-model.md`.
- [ ] T004 Refactor the public lifecycle surface in `layered-server/src/selfcheckout/analytics/AnalyticsService.java` so it can
  start workers,
  expose the existing query method, reject new intake after close begins, and close deterministically.
- [ ] T005 [P] Add a test launcher invocation in `layered-server/test.sh` for the existing
  `layered-server/test/selfcheckout/integration/InventoryInvariantCheck.java` to
  `layered-server/test.sh`.

**Checkpoint**: Shared pipeline messages and test execution are ready.

---

## Phase 3: User Story 1 - View Current Popular Items (Priority: P1) 🎯 MVP

**Goal**: Publish accurate, complete popularity rankings for the most recently processed hopping
window without changing the public analytics response.

**Independent Test**: Feed a known 1,500-scan sequence directly to analytics, wait for the
1,500th update, and verify the last 1,000 scans determine counts, ranks, and window bounds.

### Tests for User Story 1

- [ ] T006 [US1] Create the initially failing deterministic pipeline check in `layered-server/test/selfcheckout/analytics/AnalyticsPipelineCheck.java` covering
  the empty
  snapshot, first-scan snapshot, configured 1,000-scan retention, updates at scan 1 and every
  500 scans, immutable ranking, count-descending/SKU-ascending ordering, and the 1,500-scan case.

### Implementation for User Story 1

- [ ] T007 [US1] Implement the `ingest` filter and its FIFO input pipe in `layered-server/src/selfcheckout/analytics/AnalyticsService.java`; assign
  contiguous sequence
  numbers and forward each accepted scan event without ranking it on the caller thread.
- [ ] T008 [US1] Implement the `window` filter and its FIFO output pipe in `layered-server/src/selfcheckout/analytics/AnalyticsService.java`; make it
  the sole owner of
  the mutable deque, keep at most 1,000 ordered events, and emit an immutable update at scan 1 and
  each 500th scan.
- [ ] T009 [US1] Implement the `rank-and-publish` filter in `layered-server/src/selfcheckout/analytics/AnalyticsService.java`; count
  each update, resolve
  item names, sort by count descending then SKU ascending, assign consecutive ranks, and atomically
  publish an immutable snapshot.
- [ ] T010 [US1] Preserve the response mapping in `layered-server/src/selfcheckout/api/SelfCheckoutRoutes.java` so
  `GET /analytics/popular-items` continues to return the documented metadata and a limited,
  complete snapshot.
- [ ] T011 [US1] Run `AnalyticsPipelineCheck` through `layered-server/test.sh` and correct `layered-server/src/selfcheckout/analytics/AnalyticsService.java` until
  every deterministic
  assertion passes.

**Checkpoint**: A reviewer can request a complete popularity ranking based on the latest eligible
window. This is the MVP.

---

## Phase 4: User Story 2 - Complete Checkout During Analytics Processing (Priority: P2)

**Goal**: Keep successful checkout scans responsive while analytics works in the background, and
drain all accepted analytics work during orderly shutdown.

**Independent Test**: Submit scans concurrently while repeatedly reading popular items, then close
analytics and verify every accepted scan is accounted for without a partial result or deadlock.

### Tests for User Story 2

- [ ] T012 [P] [US2] Create a concurrent scan-and-read check in `layered-server/test/selfcheckout/integration/AnalyticsConcurrencyCheck.java` that
  verifies
  checkout scans retain their normal successful result while popularity readers observe only
  complete snapshots.
- [ ] T013 [P] [US2] Create an orderly-drain check in `layered-server/test/selfcheckout/integration/AnalyticsDrainCheck.java` that
  queues accepted
  scans, closes analytics, and verifies no accepted scan is silently lost or double-counted.

### Implementation for User Story 2

- [ ] T014 [US2] Add FIFO terminal-message forwarding, worker joins, and visible worker-failure handling in `layered-server/src/selfcheckout/analytics/AnalyticsService.java` so close
  drains
  ordinary messages before any worker exits.
- [ ] T015 [US2] Update `layered-server/src/selfcheckout/LayeredServerApplication.java` to own the
  analytics lifecycle and register orderly shutdown cleanup before the server process exits.
- [ ] T016 [US2] Verify `layered-server/src/selfcheckout/transactions/TransactionService.java`
  continues to call analytics only after transaction and SKU validation plus basket update, with
  the resulting call limited to successful scan submission.
- [ ] T017 [US2] Run the new concurrency and drain checks plus `layered-server/test/selfcheckout/integration/InventoryInvariantCheck.java` through `layered-server/test.sh`; resolve
  failures without changing
  `load-client/`, `mockserver/`, or `spec/self-checkout-openapi.yaml`.

**Checkpoint**: Checkout remains contract-compatible during analytics work, and orderly shutdown
does not lose accepted scans.

---

## Phase 5: User Story 3 - Verify Submission Evidence (Priority: P3)

**Goal**: Give an instructor concise, reproducible evidence of the pipeline and required workloads.

**Independent Test**: From the repository root, locate the topology description and both newly
generated report filenames, then verify one is default and one records 100 stations for 120 seconds.

### Implementation for User Story 3

- [ ] T018 [P] [US3] Update `layered-server/ARCHITECTURE.md` with the three filters, each single
  purpose, the exact `ingest → window → rank-and-publish` order, FIFO queue connections, atomic
  snapshot behavior, and shutdown-drain guarantee.
- [ ] T019 [US3] Run the supplied unmodified client with default parameters against the completed server and commit its timestamped JSON output under `layered-server/reports/`.
- [ ] T020 [US3] Run the supplied unmodified client with `--stations=100 --duration=120` against the completed server and commit its timestamped JSON output under `layered-server/reports/`.
- [ ] T021 [US3] Update `layered-server/reports/README.md` with the two new filenames, their exact
  workload settings, observed p95/p99 and throughput, errors if any, and a note that older
  200-station/180-second reports are historical rather than submission substitutes.

**Checkpoint**: The repository has a discoverable pipeline explanation and both required JSON
artifacts.

---

## Phase 6: Polish and Cross-Cutting Validation

**Purpose**: Validate the feature end to end and leave the submission easy to review.

- [ ] T022 [P] Run the build and all standalone checks through `layered-server/test.sh`, recording final pass results in `layered-server/README.md`.
- [ ] T023 Run the contract smoke checks and both workload commands in `specs/003-windowed-analytics-pipeline/quickstart.md`; reconcile
  any documentation drift in
  `layered-server/README.md`, `layered-server/ARCHITECTURE.md`, and
  `layered-server/reports/README.md`.
- [ ] T024 Inspect `git diff` and the repository report paths; record scope confirmation in `specs/003-windowed-analytics-pipeline/tasks.md` and do not modify the contract,
  load client,
  mock server, or historical reports.

---

## Dependencies and Execution Order

```text
Phase 1 (T001–T002)
  -> Phase 2 (T003–T005)
    -> US1 / MVP (T006–T011)
      -> US2 (T012–T017)
        -> US3 (T018–T021)
          -> Polish (T022–T024)
```

### User Story Dependencies

- **US1** needs only the shared pipeline lifecycle and messages from Phase 2.
- **US2** extends the working US1 pipeline with lifecycle and concurrency guarantees.
- **US3** depends on the completed server behavior from US1 and US2 so its reports are valid.

## Parallel Opportunities

- T002 and T005 modify different documentation/script files after the relevant baseline exists.
- T012 and T013 are independent test files and can be authored in parallel.
- T018 can be drafted in parallel with the end of US2, but must be checked against the final code.
- T019 and T020 are sequential execution runs against one server to keep report attribution clear.

## Implementation Strategy

### MVP First

Complete T001–T011. This produces the core three-stage pipeline and proves correct hopping-window
ranking with a deterministic 1,500-scan case.

### Incremental Delivery

1. Add test execution and shared message/lifecycle types.
2. Deliver the ordered pipeline and complete snapshots (US1).
3. Add concurrency and shutdown-drain assurances (US2).
4. Generate the two assignment-specific reports and review documentation (US3).

## Notes

- Every task uses the required checklist format, a sequential ID, and an exact file path.
- `[P]` tasks touch separate files and have no unfinished-task dependency on each other.
- Existing reports remain historical artifacts; the required new pair must be explicitly identified.

## Phase 7: Convergence

- [ ] T025 Add deterministic, concurrent-read, and orderly-drain analytics checks in `layered-server/test/selfcheckout/analytics/AnalyticsPipelineCheck.java`, `layered-server/test/selfcheckout/integration/AnalyticsConcurrencyCheck.java`, and `layered-server/test/selfcheckout/integration/AnalyticsDrainCheck.java` per FR-006 and SC-001–SC-003 (missing).
- [ ] T026 Make pipeline worker failure release downstream workers and make close complete without deadlock in `layered-server/src/selfcheckout/analytics/AnalyticsService.java` per FR-006 and Constitution III (partial).
- [ ] T027 Generate and commit newly timestamped default and `--stations=100 --duration=120` JSON reports under `layered-server/reports/`, then index both in `layered-server/reports/README.md` per FR-007 and SC-004 (missing).
- [ ] T028 Run the completed test suite via `layered-server/test.sh` and record the final commands and pass results in `layered-server/README.md` per the plan test-execution decision (partial).
