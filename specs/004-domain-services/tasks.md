---
description: "Implementation tasks for Database-Coordinated Domain Services"
---

# Tasks: Database-Coordinated Domain Services

**Input**: Design documents from `/specs/004-domain-services/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), and [quickstart.md](quickstart.md)

**Tests**: Required. The specification requires contract, integration, permission, restart, reconciliation, and workload verification.

**Organization**: Tasks are grouped by user story so each deliverable is independently testable after the shared foundation.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel after its listed prerequisites are complete.
- **[Story]**: Maps a task to one user story from [spec.md](spec.md).
- All paths are repository-relative.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish the multi-module build, constrained shared infrastructure, and deployment test layout.

- [X] T001 Replace the single-module reactor with `shared-db`, `catalog-service`, `checkout-service`, `inventory-service`, `analytics-service`, `api-gateway`, and `integration-tests` in `pom.xml`.
- [X] T002 [P] Create a `shared-db` module containing only connection configuration, transaction helpers, migrations, IDs, and observability in `shared-db/pom.xml` and `shared-db/src/main/java/edu/northeastern/cs6510/shareddb/`.
- [X] T003 [P] Create the integration-test Maven module and test runtime in `integration-tests/pom.xml` and `integration-tests/src/test/java/`.
- [X] T004 [P] Create the centralized MySQL, report-directory, and service-network baseline in `deployment/docker-compose.yml`, `deployment/mysql/init/`, and `deployment/reports/.gitkeep`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Create deployable skeletons, owned schemas, least-privilege accounts, and database-only domain interfaces.

**⚠️ CRITICAL**: Complete this phase before user-story work.

- [X] T005 Create `catalog`, `checkout`, `inventory`, and `analytics` schemas; service accounts; schema-local write grants; and only documented cross-schema grants in `deployment/mysql/init/001-service-boundaries.sql`.
- [X] T006 [P] Create the Catalog executable, configuration, owned Flyway location, and database-only readiness check in `catalog-service/pom.xml`, `catalog-service/src/main/java/`, and `catalog-service/src/main/resources/`.
- [X] T007 [P] Create the Checkout executable, configuration, owned Flyway location, and database-only readiness check in `checkout-service/pom.xml`, `checkout-service/src/main/java/`, and `checkout-service/src/main/resources/`.
- [X] T008 [P] Create the Inventory executable, configuration, owned Flyway location, and database-only readiness check in `inventory-service/pom.xml`, `inventory-service/src/main/java/`, and `inventory-service/src/main/resources/`.
- [X] T009 [P] Create the Analytics executable, configuration, owned Flyway location, and database-only readiness check in `analytics-service/pom.xml`, `analytics-service/src/main/java/`, and `analytics-service/src/main/resources/`.
- [X] T010 [P] Create the route-only API gateway executable with no database configuration in `api-gateway/pom.xml`, `api-gateway/src/main/java/`, and `api-gateway/src/main/resources/`.
- [X] T011 Create owner-specific Flyway migrations for Catalog items, Checkout transactions/basket lines/outbox, Inventory positions/movements, and Analytics checkpoints/events/snapshots in `catalog-service/src/main/resources/db/migration/`, `checkout-service/src/main/resources/db/migration/`, `inventory-service/src/main/resources/db/migration/`, and `analytics-service/src/main/resources/db/migration/`.
- [X] T012 Create the `catalog.active_items`, `checkout.accepted_scan_outbox`, `inventory.low_stock`, and `analytics.published_popularity` read-only interfaces in the owning migration paths under `*/src/main/resources/db/migration/`.
- [X] T013 Create `inventory.fulfill_checkout` to lock SKU rows in deterministic SKU order, mutate only Inventory tables, return fulfilled/unavailable quantities, and never commit in `inventory-service/src/main/resources/db/migration/`.
- [X] T014 Create tests for prohibited cross-schema writes, documented grants, and gateway database isolation in `integration-tests/src/test/java/edu/northeastern/cs6510/boundaries/DatabasePermissionTest.java` and `integration-tests/src/test/java/edu/northeastern/cs6510/boundaries/GatewayIsolationTest.java`.

**Checkpoint**: Each service builds and reaches readiness using only the central database, and no domain module contains another domain's client.

---

## Phase 3: User Story 1 - Complete a Checkout Through the Distributed System (Priority: P1) 🎯 MVP

**Goal**: Preserve synchronous, contract-compatible checkout and partial completion while Analytics can be unavailable.

**Independent Test**: Perform available and mixed-availability checkouts through the gateway with Analytics stopped; verify durable accepted scans and stock reconciliation.

### Tests for User Story 1

- [X] T015 [P] [US1] Create gateway contract tests for create, scan, status, complete, and documented errors in `api-gateway/src/test/java/edu/northeastern/cs6510/gateway/CheckoutContractTest.java`.
- [X] T016 [P] [US1] Create concurrent partial-completion and per-SKU reconciliation tests for `reserved_stock <= available_stock <= initial_stock` in `integration-tests/src/test/java/edu/northeastern/cs6510/checkout/CheckoutInventoryInvariantTest.java`.
- [X] T017 [P] [US1] Create an Analytics-outage test proving basket and outbox commit together with no Checkout-to-Analytics call in `integration-tests/src/test/java/edu/northeastern/cs6510/checkout/AnalyticsOutageCheckoutTest.java`.

### Implementation for User Story 1

- [X] T018 [US1] Implement Checkout transaction, basket, and receipt persistence with nonblank `station_id`, unique `(transaction_id, sku)`, positive quantity, and non-negative `unit_price_cents` in `checkout-service/src/main/java/edu/northeastern/cs6510/checkout/`.
- [X] T019 [US1] Implement Checkout read-only SKU validation and scan-time price capture through `catalog.active_items` in `checkout-service/src/main/java/edu/northeastern/cs6510/checkout/CheckoutRepository.java`.
- [X] T020 [US1] Implement atomic scan acceptance that persists basket changes and a unique, ordered, append-only accepted-scan outbox record in `checkout-service/src/main/java/edu/northeastern/cs6510/checkout/CheckoutService.java`.
- [X] T021 [US1] Implement Checkout completion with only `inventory.fulfill_checkout` execute permission; persist fulfilled receipt lines and retain unavailable positive quantities in `checkout-service/src/main/java/edu/northeastern/cs6510/checkout/CheckoutService.java`.
- [X] T022 [US1] Implement Checkout's gateway-facing adapter without Catalog, Inventory, or Analytics clients in `checkout-service/src/main/java/edu/northeastern/cs6510/checkout/api/CheckoutController.java`.
- [X] T023 [US1] Implement gateway-only public route and response translation for Checkout routes in `api-gateway/src/main/java/edu/northeastern/cs6510/gateway/CheckoutRouteHandler.java`.
- [X] T024 [US1] Run the User Story 1 tests in `api-gateway/src/test/java/` and `integration-tests/src/test/java/` and correct failures without adding direct domain-service communication.

**Checkpoint**: Full and partial checkout work through the unchanged public contract; accepted scans survive an Analytics outage and stock remains correct.

---

## Phase 4: User Story 2 - View Catalog, Inventory, and Popularity Information (Priority: P2)

**Goal**: Provide contract-compatible catalog, low-stock, and complete popularity views through the gateway.

**Independent Test**: Scan a known sequence, wait for publication, and request catalog, low-stock, and popular-items views through the gateway.

### Tests for User Story 2

- [X] T025 [P] [US2] Create gateway contract tests for catalog, low-stock, and popular-items routes in `api-gateway/src/test/java/edu/northeastern/cs6510/gateway/ReadModelContractTest.java`.
- [X] T026 [P] [US2] Create deterministic Analytics tests for empty snapshots, scan 1, each 500-scan update, 1,500-scan retention, and count-descending/SKU-ascending ranks in `analytics-service/src/test/java/edu/northeastern/cs6510/analytics/AnalyticsPipelineTest.java`.
- [X] T027 [P] [US2] Create an outbox replay test proving one duplicate `scan_id` creates one analytics contribution in `integration-tests/src/test/java/edu/northeastern/cs6510/analytics/AnalyticsIdempotencyTest.java`.

### Implementation for User Story 2

- [X] T028 [P] [US2] Implement Catalog persistence, validation (`sku` nonblank/max-64, `name` nonblank/max-255, non-negative `price_cents`), active-item view, and read endpoint in `catalog-service/src/main/java/edu/northeastern/cs6510/catalog/`.
- [X] T029 [P] [US2] Implement Inventory low-stock queries and owning read endpoint without any public stock-mutation path in `inventory-service/src/main/java/edu/northeastern/cs6510/inventory/`.
- [X] T030 [US2] Implement Analytics read-only outbox polling, unique processed-scan checkpointing, and recovery-safe ordered ingestion in `analytics-service/src/main/java/edu/northeastern/cs6510/analytics/OutboxConsumer.java`.
- [X] T031 [US2] Implement the internal `ingest → window → rank-and-publish` pipeline with adjacent pipes, contiguous sequences, atomic snapshots, 1,000-scan retention, and updates at scan 1/every 500 scans in `analytics-service/src/main/java/edu/northeastern/cs6510/analytics/`.
- [X] T032 [US2] Implement published-snapshot reads plus graceful stop/restart drain behavior in `analytics-service/src/main/java/edu/northeastern/cs6510/analytics/api/AnalyticsController.java`.
- [X] T033 [US2] Implement gateway-only routing and response translation for Catalog, Inventory, and Analytics routes in `api-gateway/src/main/java/edu/northeastern/cs6510/gateway/ReadModelRouteHandler.java`.
- [X] T034 [US2] Run User Story 2 contract, pipeline, and idempotency tests from `api-gateway/src/test/java/`, `analytics-service/src/test/java/`, and `integration-tests/src/test/java/` and correct failures without changing the public contract.

**Checkpoint**: Operators receive complete catalog, low-stock, and popularity results; Analytics replay and restart do not double-count accepted scans.

---

## Phase 5: User Story 3 - Operate and Verify Independent Service Units (Priority: P3)

**Goal**: Make service boundaries independently deployable, observable, permission-limited, and reviewer-verifiable.

**Independent Test**: Start each domain service with only the centralized database; verify readiness, permissions, and the absence of direct domain calls.

### Tests for User Story 3

- [X] T035 [P] [US3] Create independent-startup and Analytics-restart tests in `integration-tests/src/test/java/edu/northeastern/cs6510/deployment/IndependentStartupTest.java`.
- [X] T036 [P] [US3] Create an architecture guard that rejects domain HTTP/RPC clients, broker clients, cross-domain imports, and gateway imports in `integration-tests/src/test/java/edu/northeastern/cs6510/boundaries/NoDirectDomainCallsTest.java`.

### Implementation for User Story 3

- [X] T037 [US3] Add service images, configurations, health checks, MySQL dependency, and gateway routes to `deployment/docker-compose.yml` and `deployment/services.env.example`.
- [X] T038 [US3] Add independent-JAR launch and readiness scripts in `deployment/scripts/start-services.sh` and `deployment/scripts/check-readiness.sh`.
- [X] T039 [US3] Document deployables, schema owners, accounts, grants, gateway routes, outbox flow, and Analytics restart behavior in `deployment/ARCHITECTURE.md` and `deployment/README.md`.
- [X] T040 [US3] Run independent-startup, permission, and no-direct-call tests from `integration-tests/src/test/java/` and correct boundary violations without relaxing database grants.

**Checkpoint**: A reviewer can independently run and verify all service units and confirm database-only coordination from artifacts and tests.

---

## Phase 6: Polish & Cross-Cutting Validation

**Purpose**: Validate the completed system and capture reproducible evidence.

- [X] T041 Add full gateway contract coverage for every documented route in `integration-tests/src/test/java/edu/northeastern/cs6510/contract/FullGatewayContractTest.java`.
- [X] T042 Add a reconciliation query asserting non-negative stock and `initial stock - final stock = completed receipt-line quantity` in `integration-tests/scripts/reconcile-inventory.sql`.
- [X] T043 Run the Maven reactor, all automated checks, and quickstart procedure; record commands and results in `deployment/README.md`.
- [X] T044 Run the unchanged client against a freshly provisioned gateway at 10 stations/60 seconds and 200 stations/180 seconds; save timestamped JSON under `deployment/reports/`.
- [X] T045 Record topology, data provisioning, outcomes, mean/p95/p99, throughput, reconciliation output, and error explanations in `deployment/reports/README.md`.
- [X] T046 Inspect `git diff`, confirm no load-client, mockserver, or OpenAPI changes, and link all boundary evidence from `deployment/README.md`.

---

## Dependencies & Execution Order

```text
Phase 1: Setup
  → Phase 2: Foundational boundaries and database interfaces
    → Phase 3: US1 checkout MVP
      → Phase 4: US2 read models and Analytics
        → Phase 5: US3 deployability evidence
          → Phase 6: Full validation and workload evidence
```

### User Story Dependencies

- **US1 (P1)** depends on the shared schemas, grants, stored procedure, and skeletons; it is the MVP.
- **US2 (P2)** depends on US1's accepted-scan outbox and adds read models and Analytics publication.
- **US3 (P3)** depends on all executables and verifies their independent, database-only boundaries.

### Parallel Opportunities

- T002–T004 and T006–T010 may proceed in parallel after T001.
- T015–T017, T025–T027, and T035–T036 are independent test tasks.
- T028 and T029 are independent implementations after their foundational migrations are ready.
- T041 and T042 may proceed in parallel after user-story checkpoints pass.

## Parallel Example: User Story 1

```text
Task: "Create Checkout contract tests in api-gateway/src/test/java/edu/northeastern/cs6510/gateway/CheckoutContractTest.java"
Task: "Create inventory reconciliation tests in integration-tests/src/test/java/edu/northeastern/cs6510/checkout/CheckoutInventoryInvariantTest.java"
Task: "Create Analytics-outage test in integration-tests/src/test/java/edu/northeastern/cs6510/checkout/AnalyticsOutageCheckoutTest.java"
```

## Implementation Strategy

### MVP First

1. Complete Setup and Foundational work so ownership and direct-call guards exist before business code.
2. Complete US1 and validate synchronous partial checkout with durable outbox records.
3. Add US2 read models and Analytics publication, then US3 deployment evidence.

### Incremental Delivery

1. Foundation → independently startable, permission-limited service skeletons.
2. US1 → Checkout and Inventory correctness.
3. US2 → Catalog, low-stock, and durable Analytics visibility.
4. US3 → deployment, readiness, and no-direct-call verification.
5. Polish → full validation, reconciliation, and workload reports.

## Notes

- All 46 tasks use the required checkbox, sequential ID, and exact file path format.
- `[P]` tasks are isolated to different files and have no unfinished dependency on each other.
- The gateway may route public requests; domain services may not call one another.
