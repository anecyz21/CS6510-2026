---

description: "Task list for Independently Deployable Checkout Microservices"
---

# Tasks: Independently Deployable Checkout Microservices

**Input**: Design documents from `specs/004-domain-services/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/README.md), [quickstart.md](quickstart.md)

**Tests**: Included. The spec requires automated validation evidence: FR-032, the Independent Test
of every user story, and constitution quality gates 1–6. [quickstart.md](quickstart.md) names
the test classes. Write each story's tests before its implementation and confirm they fail first.

**Organization**: Tasks are grouped by user story so each story can be implemented and verified
as an increment. The US5 baseline is the exception. The plan requires it to be measured **before
any code change**, so it runs as Phase 2, ahead of the foundational code work.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: User story the task belongs to (US1–US5)
- Every task names exact file paths. The path aliases below expand literally.

## Path Conventions

| Alias | Expands to |
|---|---|
| `{DS}` | `domain-services` |
| `{DEP}` | `domain-services/deployment` |
| `{SC}` | `domain-services/service-contracts/src/main/java/edu/northeastern/cs6510/contracts` |
| `{SC_FIX}` | `domain-services/service-contracts/src/test/resources/fixtures` |
| `{CAT}` / `{CAT_DB}` / `{CAT_TEST}` | `domain-services/catalog-service/src/main/java/edu/northeastern/cs6510/catalog` / `domain-services/catalog-service/src/main/resources/db/migration` / `domain-services/catalog-service/src/test/java/edu/northeastern/cs6510/catalog` |
| `{CHK}` / `{CHK_DB}` / `{CHK_TEST}` | `domain-services/checkout-service/src/main/java/edu/northeastern/cs6510/checkout` / `domain-services/checkout-service/src/main/resources/db/migration` / `domain-services/checkout-service/src/test/java/edu/northeastern/cs6510/checkout` |
| `{INV}` / `{INV_DB}` / `{INV_TEST}` | `domain-services/inventory-service/src/main/java/edu/northeastern/cs6510/inventory` / `domain-services/inventory-service/src/main/resources/db/migration` / `domain-services/inventory-service/src/test/java/edu/northeastern/cs6510/inventory` |
| `{ANA}` / `{ANA_DB}` / `{ANA_TEST}` | `domain-services/analytics-service/src/main/java/edu/northeastern/cs6510/analytics` / `domain-services/analytics-service/src/main/resources/db/migration` / `domain-services/analytics-service/src/test/java/edu/northeastern/cs6510/analytics` |
| `{GW}` / `{GW_TEST}` | `domain-services/api-gateway/src/main/java/edu/northeastern/cs6510/gateway` / `domain-services/api-gateway/src/test/java/edu/northeastern/cs6510/gateway` |
| `{IT}` | `domain-services/integration-tests/src/test/java/edu/northeastern/cs6510` |

**Conventions for every task**:
- Java 21, Spring Boot 3.4.3, Spring JDBC (`JdbcTemplate`, `TransactionTemplate`) and Flyway 10.22.0.
  Do not use JPA.
- Money is `BIGINT` cents internally. Public JSON uses two-decimal numbers built with
  `BigDecimal.valueOf(cents, 2)`.
- Timestamps are `TIMESTAMP(6)` UTC.
- **Never hold a local transaction or row lock across a remote call** (FR-018). Each local step
  gets its own `TransactionTemplate.execute`.
- Public error bodies are `ApiError {error, message}`. The codes and precedence are in
  [public-api-amendment.md](contracts/public-api-amendment.md#error-catalog-after-amendment).
  Message text never contains attempt ids, reservation quantities or internal states.
- Existing migrations `V1…Vn` are immutable. Every schema change goes in a new versioned file.

---

## Phase 1: Setup (Contract Amendment and Repository Hygiene)

**Purpose**: Make the approved public contract changes and repository hygiene fixes before any
code changes (FR-034, R19, R20, F10).

- [X] T001 Apply amendments A1–A6 from `specs/004-domain-services/contracts/public-api-amendment.md` to `spec/self-checkout-openapi.yaml`:
  - Replace the `POST /transactions/{transactionId}/complete` description with the A1 text and append the A5 text. Set its `200` description to "Available units purchased (the transaction may remain OPEN if some units were unavailable)".
  - Replace the completion `409` description with the A2 text, and the scan `409` description with the A3 text.
  - Add a `503` response referencing the existing `ApiError` schema to completion (A4 text) and to all seven operations (A6 text).
  - Set `info.version: "1.2.0"`.
  - Add no paths, schemas, fields, required properties or enum values. Verify with `git diff --stat spec/` that only this file changed.
  - Do not modify `load-client/` or `mockserver/`.
- [X] T002 [P] Add a "Superseded by `specs/004-domain-services/contracts/public-api-amendment.md`" note at the top of `specs/001-self-checkout-api/contracts/error-codes.md` and next to its `INSUFFICIENT_STOCK` "refusal is terminal / transaction `CANCELLED`" guidance and its scan-time stock-claim statements. Leave the rest of the file unchanged.
- [X] T003 [P] Rewrite the two conflicting statements in `architecture-characteristics.md` so the document describes partial completion and the completion saga. Do not add a superseded note, and leave every other line unchanged.
  - **Line 9** ("Correctness of checkout behavior" row): replace "Completion is atomic: all scanned items are sold or none are sold." with a statement that completion sells every currently available unit and keeps unavailable units in the open basket for a later completion, without charging them. A completion with no available units returns `409 INSUFFICIENT_STOCK` and changes nothing. Each successful completion returns a receipt for only the units it purchased, under the same transaction ID.
  - **Line 20** (inventory-consistency priority paragraph): replace "Completion will use a single MySQL transaction … Inventory will change only after the whole basket can be fulfilled." with a description of the completion saga, built from local transactions in each service's private database, with no transaction shared across databases:
    1. Checkout freezes the basket under a stable completion-attempt ID.
    2. Inventory reserves `min(requested, available)` per SKU, using row locks and conditional updates that enforce `stock ≥ reserved ≥ 0`.
    3. Checkout durably records the receipt intent.
    4. Inventory commits the reservation, which is the only step that decrements physical stock.
    5. Checkout finalizes the receipt and reduces the basket.
    The rewrite must also say that every step is idempotent on the attempt ID, that a timeout is treated as an unknown outcome and recovered rather than compensated, and that an attempt is never cancelled once its receipt intent is durable.
  - Keep the existing trade-off paragraph on line 22. Link the rewritten paragraph to `specs/004-domain-services/research.md#r8-completion-saga` and `specs/004-domain-services/data-model.md#completion-attempt-state-machine`.
- [X] T004 [P] Add `domain-services/deployment/services.env` to the root `.gitignore` (finding F10). Confirm that `git status --short` no longer lists it and that `domain-services/deployment/services.env.example` remains tracked.
- [X] T005 [P] Move the 2026-10-16 reports into `{DEP}/reports/historical/`:
  - Move `{DEP}/reports/report-20261016-*.json` with `git mv`.
  - Move the narrative from `{DEP}/reports/README.md` to `{DEP}/reports/historical/README.md` under the heading "Historical — inadequate stock, not baseline evidence". Cite finding F8: about 12.2% of scans go to the top SKU against a seed of 1,000, and 35 SKUs ended at 0.
  - Replace `{DEP}/reports/README.md` with a stub that links `baseline/`, `redesign/`, `historical/` and `COMPARISON.md`.
  - Delete the 0-byte placeholder `{DEP}/reports/baseline/baseline-10x60.log`.

---

## Phase 2: Pre-Migration Baseline (US5, part 1: Priority P3, ordered first by the plan)

**Goal**: Record a reproducible baseline of the current service-based system with adequate
stock and bottleneck observations, before any architectural change (FR-030, FR-031, R14).

**Independent Test**: The `{DEP}/reports/baseline/` folder contains 3 normal and 3 stress
reports. Each has a matching `observations-<timestamp>.json` and a passing baseline
reconciliation output, and all were produced from tag `baseline/service-based-v1`.

**⚠️ GATE**: Do not start Phase 3 until T013 is committed (plan ordering constraint 2).

- [X] T006 [US5] Make an instrumentation-only change:
  - Add `org.springframework.boot:spring-boot-starter-actuator` to `{DS}/catalog-service/pom.xml`, `{DS}/checkout-service/pom.xml`, `{DS}/inventory-service/pom.xml`, `{DS}/analytics-service/pom.xml` and `{DS}/api-gateway/pom.xml`.
  - In each service's `src/main/resources/application.yml`, add `management.endpoints.web.exposure.include: health,metrics` and `management.metrics.distribution.percentiles-histogram.http.server.requests: true`.
  - In the four domain services only, set `spring.datasource.hikari.maximum-pool-size: 10` explicitly. This records the current default and changes nothing.
  - Change no business code and no other configuration value.
- [X] T007 [US5] In `{DS}/checkout-service/src/main/java/edu/northeastern/cs6510/checkout/CheckoutService.java`, inject `MeterRegistry` and increment the counter `checkout.db.retries` with tag `operation=scan` inside the existing deadlock-retry `catch`. Do not change `MAX_SCAN_ATTEMPTS`, the backoff or any control flow.
- [X] T008 [US5] Run `mvn -q -f domain-services/pom.xml -DskipTests package`. Commit only T006 and T007 with the message "instrumentation-only: actuator + retry counter for baseline". Then create the annotated tag `baseline/service-based-v1` on that commit. Do not push without the user's approval.
- [X] T009 [P] [US5] Create `{DEP}/provisioning/measured-stock-baseline.sql` for the **baseline schema**, where `inventory.inventory_positions` has columns `initial_stock`, `available_stock` and `reserved_stock`. It runs `UPDATE inventory_positions SET initial_stock = 1000000, available_stock = 1000000, reserved_stock = 0;` and is idempotent. Add a header comment saying it runs with the inventory owner credentials after Flyway.
- [X] T010 [P] [US5] Create `{DEP}/scripts/capture-observations.sh`. Use bash with `curl` and `jq`, and add a comment that `jq` is a prerequisite.
  - Arguments: `--out DIR`, `--topology baseline|redesign`, `--interval 5` and `--duration SECONDS`.
  - Output: one file, `DIR/observations-<UTC timestamp>.json`, containing:
    - (a) `git rev-parse HEAD` of the measured tree, the topology, pool sizes from `/actuator/metrics/hikaricp.connections.max`, the stock seed file name and the timeout settings;
    - (b) per-route `http.server.requests` and `hikaricp.connections.pending|acquire|usage`, plus `checkout.db.retries`, from each service's `/actuator/metrics`;
    - (c) MySQL `information_schema.INNODB_METRICS` rows `lock_deadlocks`, `lock_row_lock_time` and `lock_row_lock_waits`, plus the top 20 rows of `performance_schema.events_statements_summary_by_digest` by `SUM_TIMER_WAIT`;
    - (d) `docker stats --no-stream --format '{{json .}}'` samples every interval;
    - (e) redesign only: `outboxPending` and `consumerLag` from the ops exports.
  - Baseline reads MySQL through `127.0.0.1:3307` as root (password `root`). Redesign reads each instance on ports 3311–3314 with the `*_DB_ROOT_PASSWORD` values from `{DEP}/services.env`.
- [X] T011 [P] [US5] Create `{DEP}/scripts/run-measurement.sh`:
  - Arguments: `--topology baseline|redesign`, `--compose-file PATH`, `--stock-sql PATH`, `--runs 3`, `--profile normal|stress` and `--report-dir DIR`.
  - For each run:
    1. `docker compose -f PATH down -v`, then `up -d --build`, then wait for readiness.
    2. Apply the stock SQL with the inventory owner credentials.
    3. Warm up for 30 s with `load-client/run.sh --stations=10 --duration=30 --reportDir=<tmp dir>` and discard the report.
    4. Run `load-client/run.sh` with `--stations=10 --duration=60` (normal) or `--stations=200 --duration=180` (stress) and `--reportDir=DIR`, while `capture-observations.sh --duration` runs in the background.
    5. Run reconciliation: the baseline uses `domain-services/integration-tests/scripts/reconcile-inventory.sql` from the baseline tree, and the redesign uses `{DEP}/scripts/reconcile.sh --wait-settled 60`.
    6. Save the reconciliation output as `DIR/reconciliation-<timestamp>.txt`.
  - Use only documented load-client flags.
- [X] T012 [US5] Execute the baseline:
  - Run `git worktree add ../cs6510-baseline baseline/service-based-v1`.
  - Run `run-measurement.sh --topology baseline --compose-file ../cs6510-baseline/domain-services/deployment/docker-compose.yml --stock-sql {DEP}/provisioning/measured-stock-baseline.sql`, 3 times with `--profile normal` and 3 times with `--profile stress`, all with `--report-dir {DEP}/reports/baseline`.
  - Commit the 6 reports, 6 observation files and 6 reconciliation outputs. If any run has errors, keep it and explain it. Never delete a failed run.
- [X] T013 [US5] Write `{DEP}/reports/baseline/OBSERVATIONS.md`. It confirms or rejects each R14 bottleneck hypothesis with numbers from the observation files:
  - Checkout pool wait at 200 stations with 10 connections;
  - shared-instance contention;
  - two queries per scan plus a cross-schema read;
  - deadlock retries.
  It also records the host CPU, memory and OS, plus the median scan, start and complete p95 and the successful-scan throughput across the 3 stress runs.

**Checkpoint**: Baseline evidence is committed. Redesign code work may begin.

---

## Phase 3: Foundational (Blocking Prerequisites)

**Purpose**: Build the private-store topology, transport contracts, error mapping, Catalog v1
API, Checkout catalog projection with the new scan path, and gateway pass-through. Every user
story depends on these.

**⚠️ CRITICAL**: No user-story work can begin until this phase is complete.

### Build modules and transport contracts

- [ ] T014 Update the Maven reactor:
  - In `{DS}/pom.xml`, remove `<module>shared-db</module>` and delete the `{DS}/shared-db/` directory (F11). Add `<module>service-contracts</module>` before the services. Add `dependencyManagement` entries for `org.testcontainers:testcontainers-bom:1.20.4` (scope `import`) and `org.springframework.kafka:spring-kafka` (Boot-managed version).
  - Create `{DS}/service-contracts/pom.xml` (artifactId `service-contracts`, jar). Its only compile dependency is `com.fasterxml.jackson.core:jackson-annotations`. Add test dependencies `jackson-databind`, `jackson-datatype-jsr310` and `junit-jupiter`. It must have **no** JDBC, Spring or persistence dependency.
- [ ] T015 [P] Create the Catalog v1 records in `{SC}/catalog/v1/` and their fixtures in `{SC_FIX}/`:
  - `CatalogVersionV1(long catalogVersion, Instant updatedAt, int activeItemCount)`, fixture `catalog-version-v1.json`;
  - `ProductV1(long catalogVersion, String sku, String name, long priceCents)`, fixture `product-v1.json`;
  - `ProductPageV1(long catalogVersion, List<ProductPageV1.Item> items, String nextAfterSku)`, with `Item(String sku, String name, long priceCents)`, fixture `product-page-v1.json`.
  Copy fixture content from the examples in `specs/004-domain-services/contracts/catalog-internal-api.md`.
- [ ] T016 [P] Create the Inventory v1 records in `{SC}/inventory/v1/` and their fixtures in `{SC_FIX}/`:
  - `ReserveRequestV1(String transactionId, List<ReserveLineV1> lines)` and `ReserveLineV1(String sku, int quantity)`, fixture `reserve-request-v1.json`;
  - `ReservationV1(String attemptId, String transactionId, ReservationStateV1 state, List<ReservationLineV1> lines, Instant createdAt, Instant resolvedAt)` and `ReservationLineV1(String sku, int requestedQuantity, int reservedQuantity)`, fixture `reservation-v1.json`;
  - enum `ReservationStateV1 { RESERVED, COMMITTED, RELEASED, CANCELLED }`;
  - `InternalErrorV1(String error, String message, ReservationV1 reservation)`, where `reservation` is nullable and is used by the 409 bodies.
  Copy fixture content from `specs/004-domain-services/contracts/inventory-internal-api.md`.
- [ ] T017 [P] Create `{SC}/events/v1/AcceptedScanV1.java`:
  - Record `(int schemaVersion, String eventId, String eventType, String transactionId, String stationId, String sku, Instant acceptedAt, long outboxId)`.
  - Constants `TOPIC = "checkout.accepted-scans.v1"`, `EVENT_TYPE = "AcceptedScan"` and `SCHEMA_VERSION = 1`.
  - Fixture `{SC_FIX}/accepted-scan-v1.json`, copied from `specs/004-domain-services/contracts/accepted-scan-event.md`.
- [ ] T018 Create `domain-services/service-contracts/src/test/java/edu/northeastern/cs6510/contracts/FixtureCompatibilityTest.java`. For every fixture in `{SC_FIX}/`, it:
  - (a) deserializes the fixture into its record with an `ObjectMapper` that has `JavaTimeModule` registered and `FAIL_ON_UNKNOWN_PROPERTIES=false`;
  - (b) re-serializes it and asserts that every fixture field is present with an equal value;
  - (c) adds an unknown field `"futureField": 1` and asserts that deserialization still succeeds (R16 compatibility rule).

### Private-store topology

- [ ] T019 Rewrite `{DEP}/docker-compose.yml` for private stores (R2, R3, R9, R17):
  - **Databases**: four `mysql:8.4` services, `catalog-db`, `checkout-db`, `inventory-db` and `analytics-db`, published on `127.0.0.1:3311`, `3312`, `3313` and `3314`. Each has its own named volume. Each sets `MYSQL_DATABASE` (`catalog`, `checkout`, `inventory` or `analytics`), `MYSQL_USER` (`<name>_service`), `MYSQL_PASSWORD: ${<NAME>_DB_PASSWORD}` and `MYSQL_ROOT_PASSWORD: ${<NAME>_DB_ROOT_PASSWORD}`, plus a `mysqladmin ping` healthcheck. The image's entrypoint therefore creates exactly one owner database and one owner account per instance.
  - **Kafka**: `kafka` service on `apache/kafka:3.8.1`, single-node KRaft (`KAFKA_PROCESS_ROLES: broker,controller`) with volume `kafka-data`. Listeners are `PLAINTEXT://kafka:29092` for the internal network and `PLAINTEXT_HOST://localhost:9092`, published on `127.0.0.1:9092` for operators and tests. Set `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"`, `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1` and `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1`.
  - **Applications**: `catalog` (8081), `checkout` (8082), `inventory` (8083) and `analytics` (8084), published on `127.0.0.1` only for operator and test access to `/internal/**` and `/actuator`. Each has `DB_URL` pointing at its own instance only, `DB_USERNAME`, `DB_PASSWORD` and `DB_POOL_SIZE` (default 10), plus `CATALOG_URL=http://catalog:8081` where applicable, `INVENTORY_URL=http://inventory:8083` (checkout) and `KAFKA_BOOTSTRAP=kafka:29092` (checkout, analytics).
  - **Startup ordering**: each application's `depends_on` lists **only its own database** (`condition: service_healthy`).
  - **Healthchecks** use `wget -qO- http://localhost:<port>/actuator/health/readiness`, and every application sets `stop_grace_period: 25s`.
  - **Gateway**: `gateway` on `8080:8080` with no `depends_on` and the four `SERVICES_*_URL` variables.
- [ ] T020 [P] Rewrite `{DEP}/services.env.example` with placeholder keys `CATALOG_DB_PASSWORD`, `CATALOG_DB_ROOT_PASSWORD`, `CHECKOUT_DB_PASSWORD`, `CHECKOUT_DB_ROOT_PASSWORD`, `INVENTORY_DB_PASSWORD`, `INVENTORY_DB_ROOT_PASSWORD`, `ANALYTICS_DB_PASSWORD` and `ANALYTICS_DB_ROOT_PASSWORD`, each set to `change-me`. Delete `{DEP}/mysql/init/001-service-boundaries.sql` and `{DEP}/scripts/grant-cross-domain-interfaces.sh` (R2).
- [ ] T021 [P] Rewrite the operator scripts for the new topology:
  - `{DEP}/scripts/check-readiness.sh`: wait up to 180 s for every `*-db` healthcheck and for each app's `/actuator/health/readiness` to be `UP` on ports 8081–8084, plus gateway `/actuator/health` on 8080. Print a per-service status and exit non-zero on timeout.
  - `{DEP}/scripts/start-services.sh`: run `mvn -q -f domain-services/pom.xml -DskipTests package`, then `docker compose --env-file {DEP}/services.env up -d --build`, then `check-readiness.sh`.
  - Create `{DEP}/scripts/reset.sh`: `docker compose ... down -v` (removing every database volume and `kafka-data`), then `up -d --build`, then `check-readiness.sh`.

### Common service configuration and error mapping

- [ ] T022 In each `src/main/resources/application.yml` of catalog-, checkout-, inventory- and analytics-service:
  - set `spring.datasource.url/username/password` from `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`, with no committed real passwords (FR-028);
  - set `spring.datasource.hikari.maximum-pool-size: ${DB_POOL_SIZE:10}`;
  - set `server.shutdown: graceful` and `spring.lifecycle.timeout-per-shutdown-phase: 20s`;
  - set `management.endpoint.health.probes.enabled: true`, `management.endpoint.health.group.readiness.include: readinessState,db` and `management.endpoint.health.show-details: always`;
  - set `management.endpoints.web.exposure.include: health,metrics`.
  Readiness covers only the service's own database, and Flyway finishes before readiness (R17).
- [ ] T023 [P] Create `{CAT}/api/ApiError.java` (record `error`, `message`), `{CAT}/api/ApiException.java` (runtime exception carrying `HttpStatus`, `code` and `message`) and `{CAT}/api/ApiExceptionHandler.java` (`@RestControllerAdvice` that maps `ApiException` to its status and an `ApiError` body). Any other exception becomes `500` with `ApiError("INTERNAL_ERROR", "Unexpected error.")` and no stack trace.
- [ ] T024 [P] Create `{CHK}/api/ApiError.java`, `{CHK}/api/ApiException.java` and `{CHK}/api/ApiExceptionHandler.java`, following the T023 pattern. Add static factories in `ApiException`: `transactionNotFound(id)` (404 `TRANSACTION_NOT_FOUND`), `transactionNotOpen(id)` (409 `TRANSACTION_NOT_OPEN`), `transactionBusy(id)` (409 `TRANSACTION_BUSY`), `emptyBasket(id)` (409 `EMPTY_BASKET`), `insufficientStock(id)` (409 `INSUFFICIENT_STOCK`), `skuNotFound(sku)` (404 `SKU_NOT_FOUND`) and `serviceUnavailable(what)` (503 `SERVICE_UNAVAILABLE`).
- [ ] T025 [P] Create `{INV}/api/ApiError.java`, `{INV}/api/ApiException.java` and `{INV}/api/ApiExceptionHandler.java`, following the T023 pattern. The handler also maps `InternalErrorV1`-carrying exceptions to their status with the `InternalErrorV1` body, for the internal 409s.
- [ ] T026 [P] Create `{ANA}/api/ApiError.java`, `{ANA}/api/ApiException.java` and `{ANA}/api/ApiExceptionHandler.java`, following the T023 pattern.

### Owner migrations that remove cross-store objects

- [ ] T027 [P] Create `{CAT_DB}/V3__private_store.sql`. It runs `DROP VIEW active_items;` and creates `catalog_metadata` (`id TINYINT PRIMARY KEY CHECK (id = 1)`, `catalog_version BIGINT NOT NULL`, `updated_at TIMESTAMP(6) NOT NULL`) with the row `(1, 1, CURRENT_TIMESTAMP(6))`. Data-model rule: "Incremented by any migration or seed that changes item rows. Starts at 1."
- [ ] T028 [P] Create `{CHK_DB}/V4__private_store_and_projection.sql`:
  - `DROP VIEW accepted_scan_outbox;` (the V2 cross-store view; the name is reused by a table in T100).
  - `ALTER TABLE basket_lines ADD COLUMN name VARCHAR(255) NOT NULL DEFAULT '';`
  - Create `catalog_projection` (`sku VARCHAR(64) PRIMARY KEY`, `name VARCHAR(255) NOT NULL`, `price_cents BIGINT NOT NULL`, `catalog_version BIGINT NOT NULL`, `CHECK (price_cents >= 0)`).
  - Create `projection_state` (`id TINYINT PRIMARY KEY CHECK (id = 1)`, `catalog_version BIGINT NULL`, `loaded_at TIMESTAMP(6) NULL`, `last_verified_at TIMESTAMP(6) NULL`) with the row `(1, NULL, NULL, NULL)`. Data-model rule: "NULL ⇒ never loaded (`EMPTY`)".
- [ ] T029 [P] Create `{INV_DB}/V5__private_store.sql`:
  1. `DROP PROCEDURE IF EXISTS fulfill_checkout;` and `DROP VIEW low_stock;`.
  2. Drop the four V1 `CHECK` constraints on `inventory_positions`. Confirm their generated names with `SHOW CREATE TABLE`; they are expected to be `inventory_positions_chk_1`…`_4`.
  3. `ALTER TABLE inventory_positions RENAME COLUMN available_stock TO physical_stock;`
  4. Add `CONSTRAINT chk_initial_nonneg CHECK (initial_stock >= 0)`, `CONSTRAINT chk_physical_ge_reserved CHECK (physical_stock >= reserved_stock)` and `CONSTRAINT chk_reserved_nonneg CHECK (reserved_stock >= 0)`.
  Then update every `available_stock` reference in `{INV}/InventoryApplication.java` to `physical_stock`.
- [ ] T030 [P] Create `{ANA_DB}/V3__private_store.sql` with `DROP VIEW published_popularity;`. Then remove the cross-store `OutboxConsumer` poller of `checkout.accepted_scan_outbox` (finding F1) from `{ANA}/AnalyticsApplication.java`. Popular items keeps serving its existing tables until US4.

### Catalog internal v1 API

- [ ] T031 Implement Catalog internal v1 as specified in `specs/004-domain-services/contracts/catalog-internal-api.md`:
  - Add a `service-contracts` dependency to `{DS}/catalog-service/pom.xml`.
  - Create `{CAT}/internal/CatalogInternalController.java` and add these queries to `{CAT}/CatalogRepository.java`:
    - `GET /internal/v1/catalog-version` returns `CatalogVersionV1` from `catalog_metadata`, with `activeItemCount` = `COUNT(*) WHERE active = TRUE`.
    - `GET /internal/v1/products?afterSku=&limit=` returns active items in ascending SKU order, `WHERE sku > :afterSku`. `limit` defaults to 500; a value below 1 or above 1000 returns `400 INVALID_LIMIT`. Fetch `limit + 1` rows to compute `nextAfterSku`, which is `null` on the last page. Every page carries `catalogVersion`.
    - `GET /internal/v1/products/{sku}` returns `ProductV1`, or `404 SKU_NOT_FOUND` when the SKU is missing **or inactive**.
  - Make `GET /items` throw `ApiException` for errors.

### Checkout catalog projection and scan path (R6)

- [ ] T032 [P] Create `{CHK}/catalog/CatalogClient.java`:
  - It uses a JDK `HttpClient` through Spring `RestClient` with `JdkClientHttpRequestFactory`. Snapshot calls use a 300 ms connect and 2 s read timeout; `lookup(sku)` uses a 300 ms total budget. The base URL comes from `CATALOG_URL`.
  - Methods: `version()`, `page(afterSku, limit)` and `lookup(sku)`.
  - `lookup` returns a sealed result `Found(ProductV1)` / `NotFound` / `Unavailable(reason)`. A transport error or a `5xx` is `Unavailable`, never `NotFound` (FR-029).
  - Add a `service-contracts` dependency to `{DS}/checkout-service/pom.xml`.
- [ ] T033 Create `{CHK}/catalog/CatalogProjection.java`:
  - It holds a volatile immutable `Map<String, ProjectedProduct(name, priceCents)>`.
  - `state()` returns `EMPTY` when `projection_state.catalog_version IS NULL`, `CURRENT` when `now − last_verified_at ≤ catalog.projection.max-staleness` (default `60s`), and `STALE` otherwise.
  - It loads from `catalog_projection` at startup.
  - `upsertSingle(ProductV1)` inserts or updates one row and then the map; it is used by the stale or empty miss path.
  - `replaceAll(version, items)` runs in **one** local transaction: `DELETE FROM catalog_projection`, a batch `INSERT`, then `UPDATE projection_state SET catalog_version=?, loaded_at=now, last_verified_at=now`. It swaps the map only after commit.
  - `markVerified()` updates `last_verified_at`.
- [ ] T034 Create `{CHK}/catalog/CatalogProjectionLoader.java`:
  - It is `@Scheduled(fixedDelayString = "${catalog.projection.poll-interval:15s}")` and also runs once at `ApplicationReadyEvent` on the scheduler thread, so it never blocks readiness.
  - Each cycle:
    - Call `version()`. On failure, change nothing; the projection may become `STALE`.
    - If the version equals the loaded version and the projection row count is greater than 0, call `markVerified()`.
    - Otherwise, page every product with `limit=1000`. If any page's `catalogVersion` differs from the first page's, discard the load and restart, at most 3 times per cycle. After a complete load at a single version, call `replaceAll`.
  - A truncated projection (0 rows with a non-null version) triggers a full reload (rebuild rule).
  - Add `@EnableScheduling` to `{CHK}/CheckoutApplication.java`.
- [ ] T035 Create `{CHK}/scan/ProductLookup.java`, which returns `Accept(price, name)`, `UnknownSku` or `Unverifiable` per the policy table in `catalog-internal-api.md`:
  - **Projection hit**: accept at the projected price, whatever the projection state.
  - **Miss while `CURRENT`**: `UnknownSku`.
  - **Miss while `STALE` or `EMPTY`**: call `CatalogClient.lookup` once. `Found` calls `upsertSingle` and accepts; `NotFound` is `UnknownSku`; `Unavailable` is `Unverifiable`.
- [ ] T036 Create `{CHK}/scan/ScanService.java` and move the scan logic out of `{CHK}/CheckoutService.java`. Order of operations:
  1. Resolve the product with `ProductLookup` **before** opening any transaction.
  2. If the result is `UnknownSku` or `Unverifiable`, first read the transaction status without a lock, so that `404 TRANSACTION_NOT_FOUND` and `409 TRANSACTION_NOT_OPEN` take precedence. Then throw `skuNotFound` or `serviceUnavailable("catalog")`.
  3. Otherwise, in one local transaction:
     - `SELECT status FROM checkout_transactions WHERE transaction_id = ? FOR SHARE`, mapping a missing row to 404 and a non-OPEN status to 409;
     - `INSERT INTO basket_lines (transaction_id, sku, quantity, unit_price_cents, name) VALUES (?,?,1,?,?) ON DUPLICATE KEY UPDATE quantity = quantity + 1`. The price and name are captured at the first scan and never re-priced;
     - insert the existing `accepted_scan_events` row (replaced by the outbox in US4);
     - read `itemCount` and `runningTotal` with one aggregate query.
  4. Return the existing public scan response shape.
  Retry deadlock and lock-wait victims up to the existing `MAX_SCAN_ATTEMPTS`, incrementing `checkout.db.retries{operation=scan}` on each retry. Update `{CHK}/api/CheckoutController.java` to call `ScanService`.
- [ ] T037 Refactor `{CHK}/CheckoutService.java`, `{CHK}/CheckoutRepository.java` and `{CHK}/api/CheckoutController.java`:
  - `POST /transactions` and `GET /transactions/{id}` keep their public shapes and use `ApiException` (the GET reports `status`, `itemCount` and `runningTotal` from the basket).
  - Delete the `inventory.fulfill_checkout` call and every SQL string that names another schema.
  - `POST /transactions/{id}/complete` temporarily throws `ApiException.serviceUnavailable("completion")` until T059 replaces it.

### Gateway pass-through (R12, FR-005)

- [ ] T038 Create `{GW}/UpstreamProxy.java`:
  - `forward(service, HttpMethod, path, query, body, contentType)` uses a JDK `HttpClient` with a 1 s connect and 8 s read timeout (R9). It returns the upstream status, `Content-Type` and body **unchanged**, including every 4xx.
  - A `ConnectException`, `HttpTimeoutException` or `IOException` becomes `503` with body `{"error":"SERVICE_UNAVAILABLE","message":"<service> service is unavailable."}`.
  - Delete `{GW}/CheckoutClient.java` and `{GW}/HttpCheckoutClient.java`. Rewrite `{GW}/CheckoutRouteHandler.java` and `{GW}/ReadModelRouteHandler.java` to keep only the existing gateway validations (`MISSING_STATION_ID`, `MISSING_SKU`, `INVALID_LIMIT`) and then proxy.
  - Route only the seven public operations. `/internal/**` is never routed.
  - `{DS}/api-gateway/pom.xml` keeps no JDBC, Flyway, MySQL or Kafka dependency.
- [ ] T039 Update `{GW_TEST}/CheckoutContractTest.java` and `{GW_TEST}/ReadModelContractTest.java` to use a local `com.sun.net.httpserver.HttpServer` stub. Assert that:
  - (a) an upstream `409 {"error":"TRANSACTION_BUSY",…}` passes through with the same status and body;
  - (b) a closed upstream port yields `503 SERVICE_UNAVAILABLE`;
  - (c) `MISSING_SKU` and `MISSING_STATION_ID` are still produced by the gateway without calling upstream.

### System-test support

- [ ] T040 Update `{DS}/integration-tests/pom.xml` with test dependencies `org.testcontainers:junit-jupiter`, `mysql`, `kafka`, `toxiproxy` (BOM 1.20.4), `com.fasterxml.jackson.core:jackson-databind`, `jackson-datatype-jsr310` and `service-contracts`. Create:
  - `{IT}/support/Stack.java`:
    - **Endpoints**: gateway URL `http://localhost:8080`; ops base URLs `http://127.0.0.1:8081`–`8084`; owner JDBC URLs `jdbc:mysql://127.0.0.1:331{1..4}/<db>`, with credentials read from `domain-services/deployment/services.env` or environment variables.
    - **Compose control**: `compose(String... args)`, which runs `docker compose -f domain-services/deployment/docker-compose.yml --env-file domain-services/deployment/services.env …` through `ProcessBuilder`. Helpers `stop`, `start`, `pause`, `unpause`, `kill` and `awaitReady(service, Duration)`.
    - **Fail fast**: a `@BeforeAll`-friendly `requireRunning()` that **fails** with the message "start the stack: domain-services/deployment/scripts/start-services.sh" when the gateway is unreachable. It never silently skips.
  - `{IT}/support/PublicApi.java`: typed calls for the seven public operations, each returning `Response(int status, JsonNode body)`.

**Checkpoint**: The stack starts with four private databases and Kafka. Scans work through the
gateway with projected prices. Errors pass through the gateway unchanged, and completion returns
503 until US1.

---

## Phase 4: User Story 1 - Purchase Available Items Without Overselling (Priority: P1) 🎯 MVP

**Goal**: Completion reserves only available stock, purchases it with a per-attempt receipt,
keeps the shortfall in the open basket, returns `409 INSUFFICIENT_STOCK` when nothing is
available, and never oversells under concurrency (FR-007, FR-008, FR-011–FR-015, FR-019).

**Independent Test**: quickstart V5 steps 1–5 and V6 (`PartialFulfillmentTest`,
`CompletionPreconditionTest`, `ConcurrentLastUnitTest`), each ending with
`reconcile.sh` checks R-1…R-5 passing.

### Tests for User Story 1 ⚠️ (write first and confirm they fail)

- [ ] T041 [P] [US1] Create `{INV_TEST}/reservation/ReservationServiceIT.java`, using a Testcontainers `MySQLContainer("mysql:8.4")` with Flyway applying `{INV_DB}`. Cover:
  - **reserve** grants `min(quantity, physical − reserved)` per SKU, and a SKU without a position reserves 0;
  - Σ reserved = 0 → state `RELEASED`, nothing held;
  - a repeat with the same id returns the stored resource with `200`, with no second effect;
  - the same id with different lines → `409 RESERVATION_MISMATCH`;
  - **commit** moves `physical_stock −= reserved` and `reserved_stock −= reserved`, and writes exactly one `SALE` movement per `(attempt_id, sku)`;
  - a repeat commit has no effect;
  - committing a `RELEASED` reservation → `409 RESERVATION_NOT_COMMITTABLE`;
  - **concurrency**: 50 threads reserve against a SKU with `physical_stock = 1`. Exactly one grant of 1, and `0 ≤ reserved_stock ≤ physical_stock` throughout.
- [ ] T042 [P] [US1] Create `{CHK_TEST}/completion/AttemptDriverTest.java`, a unit test with a fake `InventoryClient` and an H2-free in-memory fake of `CompletionAttemptRepository`. Cover:
  - full reserve → `COMMITTING` → `FINALIZED`;
  - partial reserve stores `reserved_quantity` per line;
  - all-zero reserve → `REJECTED`, with `active_attempt_id` cleared;
  - reserve returning a `CANCELLED` tombstone → `CANCELLED`;
  - a lost conditional-update race (0 rows updated) re-reads the attempt instead of failing;
  - no `InventoryClient` call happens inside a repository transaction callback (assert with a flag set by the fake repository).
- [ ] T043 [P] [US1] Create `{IT}/completion/PartialFulfillmentTest.java` implementing quickstart V5 steps 1–5:
  1. Set `SKU-000001`=2 and `SKU-000002`=1, plus a zero-stock SKU (`SKU-000003`=0). Scan A×3, B×1 and the zero-stock SKU once. Expect `200` each time, and Inventory physical and reserved stock unchanged (US1-1).
  2. Complete. Expect `200` with A×2 and B×1, `itemCount=3` and total `2·pA+pB` in exact cents. `GET` shows `OPEN` with the remaining units (US1-3).
  3. Complete again. Expect `409 INSUFFICIENT_STOCK`, with the basket, stock and receipt count unchanged (US1-6).
  4. Restock A+1 and the zero-stock SKU+1, then complete. Expect the same `transactionId` and `startedAt`, a new `completedAt`, only the newly purchased units, and status `COMPLETED` (US1-4).
  5. Complete again. Expect `409 TRANSACTION_NOT_OPEN`.
  Finish with `Reconciliation.assertPass()`.
- [ ] T044 [P] [US1] Create `{IT}/completion/CompletionPreconditionTest.java` (US1-7). Cover:
  - complete on an unknown id → `404 TRANSACTION_NOT_FOUND`;
  - complete on an open transaction with an empty basket → `409 EMPTY_BASKET`;
  - complete on a completed transaction → `409 TRANSACTION_NOT_OPEN`;
  - scan on an unknown transaction → `404 TRANSACTION_NOT_FOUND`;
  - scan `SKU-999999` on an open transaction while the projection is `CURRENT` → `404 SKU_NOT_FOUND`;
  - scan on a completed transaction → `409 TRANSACTION_NOT_OPEN`.
  Assert every body has exactly the fields `error` and `message`.
- [ ] T045 [P] [US1] Create `{IT}/completion/ConcurrentLastUnitTest.java` (quickstart V6):
  - (a) 50 transactions on distinct stations each scan `SKU-000010` (stock 1) and `SKU-000011` (stock 1000), then complete simultaneously through an `ExecutorService`. Expect exactly one receipt containing `SKU-000010`, and the other 49 receipts each to contain `SKU-000011` with their transactions still `OPEN` holding `SKU-000010`.
  - (b) 200 parallel completions against random stocks from 0 to 3.
  End each case with `Reconciliation.assertPass()`. Delete the obsolete `{IT}/checkout/CheckoutInventoryInvariantTest.java`.

### Implementation for User Story 1

- [ ] T046 [P] [US1] Create `{INV_DB}/V6__reservations.sql`:
  - `reservations`: `attempt_id VARCHAR(64) PRIMARY KEY`, `transaction_id VARCHAR(64) NOT NULL`, `state VARCHAR(16) NOT NULL CHECK (state IN ('RESERVED','COMMITTED','RELEASED','CANCELLED'))`, `created_at TIMESTAMP(6) NOT NULL`, `resolved_at TIMESTAMP(6) NULL`.
  - `reservation_lines`: `attempt_id VARCHAR(64) NOT NULL`, `sku VARCHAR(64) NOT NULL`, `requested_quantity INT NOT NULL`, `reserved_quantity INT NOT NULL`, `PRIMARY KEY (attempt_id, sku)`, `CHECK (requested_quantity >= 1)`, `CHECK (reserved_quantity >= 0 AND reserved_quantity <= requested_quantity)` (data model: "0 ≤ reserved ≤ requested").
  - `inventory_movements`:
    - add `attempt_id VARCHAR(64) NULL` and `movement_type VARCHAR(16) NOT NULL DEFAULT 'SALE' CHECK (movement_type IN ('SALE','PROVISION'))`;
    - rename `stock_after` to `physical_after`;
    - add `CHECK (movement_type <> 'SALE' OR attempt_id IS NOT NULL)` (data model: "Every committed decrement references a durable attempt");
    - add `UNIQUE KEY uq_sale_attempt_sku (attempt_id, sku)`. NULL attempt ids on `PROVISION` rows do not collide.
- [ ] T047 [P] [US1] Create `{CHK_DB}/V5__completion_attempts.sql`:
  - **`checkout_transactions`**: add `active_attempt_id VARCHAR(64) NULL` and `version BIGINT NOT NULL DEFAULT 0`.
  - **`completion_attempts`**:
    - Columns: `attempt_id VARCHAR(64) PRIMARY KEY`, `transaction_id VARCHAR(64) NOT NULL` (indexed), `state VARCHAR(16) NOT NULL CHECK (state IN ('RESERVING','COMMITTING','CANCELLING','FINALIZED','REJECTED','CANCELLED'))`, `created_at` and `updated_at TIMESTAMP(6) NOT NULL`, `finalized_at TIMESTAMP(6) NULL`, `step_failures INT NOT NULL DEFAULT 0`, `next_retry_at TIMESTAMP(6) NOT NULL`, `lease_owner VARCHAR(64) NULL`, `lease_until TIMESTAMP(6) NULL`, `needs_attention BOOLEAN NOT NULL DEFAULT FALSE` and `last_error VARCHAR(512) NULL`.
    - Index `(state, next_retry_at)`.
  - **`completion_attempt_lines`**:
    - Columns: `attempt_id`, `sku`, `requested_quantity INT NOT NULL CHECK (requested_quantity > 0)`, `unit_price_cents BIGINT NOT NULL`, `name VARCHAR(255) NOT NULL` and `reserved_quantity INT NULL`.
    - Primary key `(attempt_id, sku)`.
    - `CHECK (reserved_quantity IS NULL OR (reserved_quantity >= 0 AND reserved_quantity <= requested_quantity))`.
  - **Receipts**: `DROP TABLE receipt_lines; DROP TABLE receipts;` (development data is not migrated, R13). Then recreate:
    - `receipts`: `attempt_id VARCHAR(64) PRIMARY KEY`, `transaction_id VARCHAR(64) NOT NULL` (indexed) and `completed_at TIMESTAMP(6) NOT NULL`;
    - `receipt_lines`: `attempt_id`, `sku`, `quantity INT NOT NULL CHECK (quantity > 0)`, `unit_price_cents BIGINT NOT NULL CHECK (unit_price_cents >= 0)` and `name VARCHAR(255) NOT NULL`, with primary key `(attempt_id, sku)`.
- [ ] T048 [US1] Create `{INV}/reservation/ReservationRepository.java`:
  - `lockReservation(attemptId)`: `SELECT … FOR UPDATE`;
  - `insertReservation`;
  - `lockPositions(skus)`: `SELECT sku, physical_stock, reserved_stock FROM inventory_positions WHERE sku IN (…) ORDER BY sku FOR UPDATE`;
  - conditional `reserve(sku, qty)`: `UPDATE inventory_positions SET reserved_stock = reserved_stock + ? WHERE sku = ? AND physical_stock - reserved_stock >= ?`;
  - `commitLine`: `UPDATE inventory_positions SET physical_stock = physical_stock - ?, reserved_stock = reserved_stock - ? WHERE sku = ? AND reserved_stock >= ?`;
  - `releaseLine`;
  - `insertSaleMovement(attemptId, sku, -qty, physicalAfter)`;
  - `updateState(attemptId, state, resolvedAt)`;
  - `find(attemptId)` returning `ReservationV1`.
- [ ] T049 [US1] Create `{INV}/reservation/ReservationService.java` with `reserve(attemptId, ReserveRequestV1)`, `commit(attemptId)` and `get(attemptId)`:
  - Each runs in **one** local transaction: lock or insert the reservation row first, then the positions in ascending SKU order (R11).
  - **reserve**:
    - Validate 1–1,000 distinct SKUs, each with `quantity ≥ 1`; otherwise `400 INVALID_RESERVATION_REQUEST`.
    - If the id is new, grant `min(quantity, physical − reserved)` and set the state to `RESERVED` when Σ > 0, else `RELEASED`.
    - If the id exists, return it unchanged (`200`), or `409 RESERVATION_MISMATCH` when the lines differ.
  - **commit**: implement exactly the table in `specs/004-domain-services/contracts/inventory-internal-api.md`.
  - Retry `CannotAcquireLockException` and `DeadlockLoserDataAccessException` up to 3 times, incrementing `inventory.db.retries{operation}`. After that, return `503 SERVICE_UNAVAILABLE`.
- [ ] T050 [US1] Create `{INV}/reservation/ReservationController.java` with `PUT /internal/v1/reservations/{attemptId}` (`201` when created, `200` when it existed), `POST /internal/v1/reservations/{attemptId}/commit` and `GET /internal/v1/reservations/{attemptId}`. 404 returns `RESERVATION_NOT_FOUND`, and the 409 bodies are `InternalErrorV1` including the resource. Add a `service-contracts` dependency to `{DS}/inventory-service/pom.xml`.
- [ ] T051 [P] [US1] Create `{INV}/ops/InventoryOpsController.java` with `GET /internal/v1/ops/reconciliation-export`, which is read-only. It returns `positions` (an array of `{sku, initialStock, physicalStock, reservedStock}`), `openReservations` (the count in `RESERVED`), `committedAttemptIds`, and `saleQuantityBySku` (Σ |SALE `quantity_delta`| per SKU, an additive field).
- [ ] T052 [P] [US1] Create `{CHK}/inventory/InventoryClient.java`:
  - It uses `RestClient` over a JDK `HttpClient` with a 300 ms connect and 1,500 ms read timeout. The base URL comes from `INVENTORY_URL`.
  - Methods: `reserve(attemptId, ReserveRequestV1)`, `commit(attemptId)`, `cancel(attemptId)` and `get(attemptId)`. They return a `ReservationV1`, or a typed `Conflict(code, ReservationV1)` for 409s.
  - Transport errors, timeouts and `5xx` throw `InventoryUnavailableException` (an unknown outcome). The client never retries; retries belong to the driver (T073).
- [ ] T053 [P] [US1] Create `{CHK}/completion/UuidV7.java`, a static `String next()` generating RFC 9562 UUIDv7 values (48-bit Unix milliseconds, version 7, variant 10, random bits from `SecureRandom`). Add `{CHK_TEST}/completion/UuidV7Test.java`, which checks the version and variant bits and monotonic time ordering across milliseconds.
- [ ] T054 [US1] Create `{CHK}/completion/AttemptState.java` (enum `RESERVING`, `COMMITTING`, `CANCELLING`, `FINALIZED`, `REJECTED`, `CANCELLED` with `isTerminal()`), `{CHK}/completion/CompletionAttempt.java`, `{CHK}/completion/AttemptLine.java` (records) and `{CHK}/completion/CompletionAttemptRepository.java`. The repository provides:
  - `insertAttemptWithLines`, `find(attemptId)` (attempt plus lines) and `findActiveForTransaction`;
  - conditional transitions `transition(attemptId, from, to)`: `UPDATE completion_attempts SET state=?, updated_at=? WHERE attempt_id=? AND state=?`, returning a boolean;
  - `storeReserved(attemptId, Map<sku,qty>)`;
  - `recordStepFailure(attemptId, error, nextRetryAt)`, which truncates `last_error` to 512 characters;
  - `clearActiveAttempt(transactionId, attemptId)`: `… SET active_attempt_id = NULL, version = version + 1 WHERE transaction_id=? AND active_attempt_id=?`.
- [ ] T055 [US1] Create `{CHK}/completion/CompletionService.java`. `complete(transactionId)` runs the freeze as one local transaction:
  1. `SELECT status, active_attempt_id FROM checkout_transactions WHERE transaction_id=? FOR UPDATE`.
  2. Apply precedence 1–2 (`TRANSACTION_NOT_FOUND`, `TRANSACTION_NOT_OPEN`). Precedence 3 (active attempt) throws `serviceUnavailable("completion")` until T074 adds the join.
  3. Precedence 4: no basket lines → `EMPTY_BASKET`.
  4. Insert the attempt (`UuidV7.next()`, state `RESERVING`, `next_retry_at = now`), copy every basket line into `completion_attempt_lines` (immutable snapshot: `requested_quantity`, `unit_price_cents`, `name`), and set `active_attempt_id` and `version = version + 1`.
  After the commit, call `AttemptDriver.drive(attemptId, deadline)` and map its terminal outcome: `FINALIZED` → receipt, `REJECTED` → `insufficientStock`, `CANCELLED` → `serviceUnavailable`.
- [ ] T056 [US1] Modify `{CHK}/scan/ScanService.java` to read `SELECT status, active_attempt_id … FOR SHARE`. A non-null `active_attempt_id` throws `ApiException.transactionBusy(id)` immediately, before any basket write and before any event or outbox insert (FR-011, R10). Precedence: `NOT_FOUND`, then `NOT_OPEN`, then `BUSY`, then SKU outcomes. Keep the precedence for the unknown-SKU path from T036 consistent by also checking `active_attempt_id` in its non-locking pre-read.
- [ ] T057 [US1] Create `{CHK}/completion/AttemptDriver.java`. It drives one attempt step by step until it reaches a terminal state or the deadline, re-reading the attempt before every step, and every local write is its own `TransactionTemplate.execute`:
  - **RESERVING**:
    - Call `inventory.reserve(attemptId, lines)`.
    - On `RESERVED`, validate that the returned SKUs equal the attempt lines and that `0 ≤ reserved ≤ requested`. Then, in a local transaction, `storeReserved` and transition `RESERVING→COMMITTING` (the durable receipt intent).
    - On `RELEASED` (all zero), in a local transaction, transition `RESERVING→REJECTED` and `clearActiveAttempt`.
    - On `CANCELLED`, in a local transaction, transition `RESERVING→CANCELLED` and `clearActiveAttempt`.
  - **COMMITTING**: call `inventory.commit` and require `COMMITTED`, then finalize (T058).
  - If any validation fails, set `needs_attention = TRUE` and `last_error`, and stop.
  - When a conditional transition returns false, re-read and continue from the observed state.
- [ ] T058 [US1] Implement `finalize(attemptId)` in `{CHK}/completion/AttemptDriver.java`. It runs as one local transaction:
  1. Transition `COMMITTING→FINALIZED` with `finalized_at = now` (if this returns false, re-read).
  2. Insert `receipts(attempt_id, transaction_id, completed_at = finalized_at)`.
  3. Insert a `receipt_lines` row for each line with `reserved_quantity > 0` (`quantity = reserved_quantity`, plus the captured `unit_price_cents` and `name`).
  4. For each purchased line, `UPDATE basket_lines SET quantity = quantity - ? WHERE transaction_id=? AND sku=? AND quantity >= ?`, then `DELETE … WHERE quantity = 0`.
  5. If no basket lines remain, `UPDATE checkout_transactions SET status='COMPLETED', completed_at=?`.
  6. `clearActiveAttempt`.
  Then create `{CHK}/completion/ReceiptAssembler.java`. It builds the public `Receipt` exactly per the OpenAPI `Receipt` schema from the `receipts` and `receipt_lines` rows of **this attempt only**, plus the transaction's `transactionId`, `stationId` and `startedAt`. `itemCount` is Σ quantity and `totalAmount` is Σ quantity × cents / 100, both exact (FR-014).
- [ ] T059 [US1] Wire `POST /transactions/{id}/complete` in `{CHK}/api/CheckoutController.java` to `CompletionService`, replacing the T037 placeholder. Delete the leftover completion and receipt code from `{CHK}/CheckoutService.java` and `{CHK}/CheckoutRepository.java`. Add the counter `checkout.completion.outcome{result=receipt|insufficient|unavailable}`.
- [ ] T060 [US1] Create `{CHK}/ops/OperationsController.java` with `GET /internal/v1/ops/reconciliation-export`, which is read-only. It returns `unresolvedAttempts` (the count in `RESERVING`, `COMMITTING` or `CANCELLING`), `activeAttemptFlags` (the count of non-null `active_attempt_id`), `receiptQuantityBySku` (Σ `receipt_lines.quantity` per SKU) and `finalizedAttemptIds`, per `specs/004-domain-services/contracts/checkout-operations-api.md`. `outboxPending` and `outboxTotal` are added in T106.
- [ ] T061 [P] [US1] Create `{DEP}/provisioning/restock-sku.sql`, parameterized with `SET @sku = 'SKU-…'; SET @qty = N;`. In one transaction it runs `UPDATE inventory_positions SET initial_stock = initial_stock + @qty, physical_stock = physical_stock + @qty WHERE sku = @sku` and inserts a `PROVISION` movement with a NULL attempt. Create `{IT}/support/InventoryProvisioning.java` with `setStock(sku, n)`, which adjusts both columns by the delta and writes a `PROVISION` movement, and `addStock(sku, n)`, which runs the same statements through the inventory owner's JDBC credentials.
- [ ] T062 [US1] Create `{DEP}/scripts/reconcile.sh`, using bash with `curl` and `jq`:
  - Options: `--wait-settled SECONDS`, which polls until `unresolvedAttempts == 0` and `openReservations == 0` or the timeout expires, and `--out FILE`.
  - Read the two exports from `127.0.0.1:8082` and `127.0.0.1:8083`, never a database.
  - Evaluate checks R-1…R-5 from `specs/004-domain-services/data-model.md#settled-state-reconciliation-fr-019-sc-005`. R-4 is `initialStock − physicalStock == receiptQuantityBySku[sku]` for every SKU. R-5 is set equality of `committedAttemptIds` and `finalizedAttemptIds`, plus `saleQuantityBySku == receiptQuantityBySku`.
  - Print one line per check and every failing SKU. The last line is `RECONCILIATION PASS` (exit 0) or `RECONCILIATION FAIL` (exit 1).
  Also create `{IT}/support/Reconciliation.java`, whose `assertPass()` runs `bash domain-services/deployment/scripts/reconcile.sh --wait-settled 60` and asserts exit code 0.

**Checkpoint**: The MVP is done. Partial purchases, per-attempt receipts, zero-stock 409 and
concurrent last-unit competition work and reconcile. T043–T045 pass.

---

## Phase 5: User Story 2 - Recover a Purchase After an Interrupted Completion (Priority: P1)

**Goal**: Interrupted, timed-out or duplicated completions resolve to exactly one committed
purchase or one released attempt. The synchronous request returns `503` within its deadline,
busy scans are rejected, and unresolved attempts are visible to operators (FR-011, FR-015–FR-018).

**Independent Test**: quickstart V5 step 6 and V7 cases a–h (`BusyScanTest`,
`InterruptionRecoveryTest`, `LostResponseTest`, `InventoryOutageTest`,
`DuplicateCompletionJoinTest`, `CancelCommitRaceTest`). Each ends with `reconcile.sh`
passing within 60 s of dependencies becoming healthy (SC-004).

### Tests for User Story 2 ⚠️ (write first and confirm they fail)

- [ ] T063 [P] [US2] Create `{INV_TEST}/reservation/ReservationCancelIT.java` (Testcontainers MySQL). Cover:
  - cancel on `RESERVED` → `RELEASED`, with `reserved_stock` restored and `physical_stock` unchanged;
  - cancel on an unknown id → `CANCELLED` tombstone, after which a reserve with the same id returns `CANCELLED` and reserves 0;
  - cancel on `COMMITTED` → `409 RESERVATION_COMMITTED` with the resource;
  - a repeat cancel is idempotent;
  - commit and cancel raced from two threads on one `RESERVED` id produce exactly one winner, and the loser gets its 409.
- [ ] T064 [P] [US2] Create `{CHK_TEST}/recovery/RecoveryWorkerTest.java`, a unit test with a fake clock, a fake `InventoryClient` and a fake repository. Cover:
  - an eligible attempt is leased once, and a second worker skips it while `lease_until > now`;
  - backoff follows `250 ms × 2ⁿ`, capped at 5 s;
  - a `RESERVING` attempt older than `cancel-after` (30 s) after a failure → `CANCELLING`;
  - a `COMMITTING` attempt is never moved to `CANCELLING`, whatever its age (forward-only);
  - `needs_attention` is set after 20 consecutive failures while retrying continues;
  - `409 RESERVATION_COMMITTED` received in `CANCELLING` sets `needs_attention` and stops.
- [ ] T065 [P] [US2] Create `{IT}/completion/BusyScanTest.java` (quickstart V5 step 6, US2-4, SC-001):
  1. Pause Inventory with `Stack.pause("inventory")`.
  2. Start a completion on a background thread.
  3. While it is unresolved, scan the same transaction. Expect `409 TRANSACTION_BUSY` in under 1 s, the basket line count unchanged, and the Checkout outbox or event count unchanged (`accepted_scan_events` before US4, `outboxTotal` after).
  4. Unpause Inventory, then assert the attempt resolves and `Reconciliation.assertPass()`.
- [ ] T066 [P] [US2] Create `{IT}/completion/InterruptionRecoveryTest.java` (quickstart V7 a–d). For each point `AFTER_FREEZE`, `AFTER_RESERVE_RESPONSE`, `AFTER_PREPARE` and `AFTER_COMMIT_RESPONSE`:
  1. Arm `Faults.haltAt(point)`, then call complete. The JVM halts.
  2. Restart Checkout with `Stack.start("checkout")` and `awaitReady`.
  3. Within 60 s, assert there is one attempt id for the transaction and either one receipt with stock decremented once, or (a and b only) a `CANCELLED` attempt with reserved stock returned to 0.
  4. Assert `Reconciliation.assertPass()`.
- [ ] T067 [P] [US2] Create `{IT}/completion/LostResponseTest.java` (quickstart V7 e):
  1. Add a Toxiproxy `limit_data` downstream toxic on the inventory proxy only for the commit call. Using `Faults`, arm it after the reserve has passed.
  2. Complete. Expect `503 SERVICE_UNAVAILABLE` within 5 s plus 1 s tolerance.
  3. Remove the toxic. Recovery finalizes exactly once: one receipt row and one `SALE` movement per SKU.
  4. Call complete again. Expect `409 TRANSACTION_NOT_OPEN`, or a receipt for only the remaining units.
  5. Assert `Reconciliation.assertPass()`.
- [ ] T068 [P] [US2] Create `{IT}/completion/InventoryOutageTest.java` (quickstart V7 f, US2-6):
  1. Run `Stack.stop("inventory")` for 45 s while 10 transactions complete. Each request must return `503 SERVICE_UNAVAILABLE` within 6 s.
  2. `GET http://127.0.0.1:8082/internal/v1/ops/completion-attempts` lists the unresolved attempts.
  3. Start Inventory again. Within 60 s there is no unresolved attempt and no `RESERVED` reservation.
  4. Assert `Reconciliation.assertPass()`.
- [ ] T069 [P] [US2] Create `{IT}/completion/DuplicateCompletionJoinTest.java` (quickstart V7 g, A5). Add 500 ms latency to the inventory proxy, then fire 5 concurrent completes for one transaction. Expect exactly one `completion_attempts` row for the transaction (through the ops list `state=all`), and all 5 callers receive the same status and the same receipt body.
- [ ] T070 [P] [US2] Create `{IT}/completion/CancelCommitRaceTest.java` (quickstart V7 h). Run Checkout with `CHECKOUT_RECOVERY_CANCEL_AFTER=1s` (faults override), and add a Toxiproxy latency of 1,200 ms on the commit path. Over 20 iterations, each attempt ends either `FINALIZED` with a `COMMITTED` reservation, or `CANCELLED` with a `RELEASED` or `CANCELLED` reservation, never mixed (FR-017). Assert `Reconciliation.assertPass()`.

### Implementation for User Story 2

- [ ] T071 [US2] Add `cancel(attemptId)` to `{INV}/reservation/ReservationService.java` and `POST /internal/v1/reservations/{attemptId}/cancel` to `{INV}/reservation/ReservationController.java`, exactly per the cancel table in `inventory-internal-api.md`:
  - `RESERVED` → `RELEASED`, with `reserved_stock −= reserved` and physical stock unchanged;
  - `RELEASED`/`CANCELLED` → `200`, same state;
  - `COMMITTED` → `409 RESERVATION_COMMITTED` with the resource;
  - not found → insert a `CANCELLED` tombstone with no lines and return `200`.
  It locks in the same order as commit, so commit and cancel are mutually exclusive.
- [ ] T072 [US2] Create `{CHK}/completion/CompletionProperties.java` (`@ConfigurationProperties("checkout.completion")`, `requestDeadline` default `5s`) and `{CHK}/recovery/RecoveryProperties.java` (`@ConfigurationProperties("checkout.recovery")`):
  - `scanInterval` 1s, `cancelAfter` 30s, `attentionAfter` 20, `leaseDuration` 10s;
  - `backoffBase` 250ms, `backoffCap` 5s;
  - `inRequestRetries` 3, with backoffs 100/200/400 ms.
  Bind them in `{DS}/checkout-service/src/main/resources/application.yml` with environment overrides such as `CHECKOUT_RECOVERY_CANCEL_AFTER`. Every value is recorded by `capture-observations.sh`.
- [ ] T073 [US2] Add deadline and retry handling to `{CHK}/completion/AttemptDriver.java`:
  - **Deadline**: `drive(attemptId, deadline)` stops at the deadline. On `InventoryUnavailableException`, retry the **same** command with the **same** `attemptId` up to `inRequestRetries` times, sleeping 100/200/400 ms, never past the deadline.
  - **On exhaustion or deadline**: in a local transaction, `recordStepFailure(attemptId, error, now + backoff)` and release the lease. The caller then returns `503 SERVICE_UNAVAILABLE`, does not cancel the attempt, and does not restore stock (FR-016).
  - **Leases**: before driving, `CompletionAttemptRepository.acquireLease(attemptId, owner, leaseDuration)` runs a conditional `UPDATE … WHERE attempt_id=? AND (lease_until IS NULL OR lease_until < now OR lease_owner = ?)`. If the lease is not acquired, the request polls the attempt state every 50 ms until it is terminal or the deadline passes.
  - **On progress**: reset `step_failures` to 0.
- [ ] T074 [US2] Implement precedence rule 3 (join, A5) in `{CHK}/completion/CompletionService.java`, replacing the T055 placeholder. When the freeze transaction sees a non-null `active_attempt_id`, commit without writes and call `AttemptDriver.drive(activeAttemptId, deadline)` for the same attempt. Return its outcome: `FINALIZED` → that attempt's receipt from `ReceiptAssembler`, `REJECTED` → `409 INSUFFICIENT_STOCK`, and `CANCELLED` or deadline → `503`. Increment `checkout.completion.outcome{result=joined}`.
- [ ] T075 [US2] Add the `CANCELLING` step to `{CHK}/completion/AttemptDriver.java`:
  - Call `inventory.cancel`.
  - On `RELEASED` or `CANCELLED`, in a local transaction, transition `CANCELLING→CANCELLED` and `clearActiveAttempt`. The basket stays unchanged and the transaction stays `OPEN`.
  - On `409 RESERVATION_COMMITTED`, set `needs_attention` and `last_error = "cancel observed COMMITTED"`, and stop. This is impossible by design (data-model table).
  - On an unknown outcome, record the failure and back off.
- [ ] T076 [US2] Create `{CHK}/recovery/RecoveryWorker.java`:
  - **Schedule**: `@Scheduled(fixedDelayString="${checkout.recovery.scan-interval:1s}")` with lease owner id `<hostname>-<uuid>`.
  - **Selection**: up to 50 attempts `WHERE state IN ('RESERVING','COMMITTING','CANCELLING') AND next_retry_at <= now AND (lease_until IS NULL OR lease_until < now) ORDER BY next_retry_at`.
  - **For each attempt**:
    - acquire the lease;
    - if the state is `RESERVING`, there was a failure (`step_failures > 0`) and `created_at < now − cancelAfter`, transition `RESERVING→CANCELLING`, which is never possible from `COMMITTING`;
    - drive one step through `AttemptDriver`;
    - on failure, set `next_retry_at = now + min(backoffBase × 2^step_failures, backoffCap)`, and set `needs_attention = TRUE` once `step_failures ≥ attentionAfter`, while retrying continues;
    - release the lease.
  - **Metrics**: `checkout.recovery.steps{step,result}`, plus the gauge `checkout.completion.attempts{state}`, refreshed every scan.
  - The worker never holds a transaction while calling Inventory.
- [ ] T077 [US2] Extend `{CHK}/ops/OperationsController.java` per `checkout-operations-api.md`:
  - `GET /internal/v1/ops/completion-attempts?state=unresolved|needsAttention|all&limit=`, with default `unresolved` and limit 100, maximum 1000 (otherwise `400 INVALID_LIMIT`), returning attempts with their lines;
  - `GET /internal/v1/ops/completion-attempts/{attemptId}`, returning `200` or `404 ATTEMPT_NOT_FOUND`;
  - `POST /internal/v1/ops/completion-attempts/{attemptId}/retry`, which sets `next_retry_at = now` and `needs_attention = FALSE` and returns `202`, or `409 ATTEMPT_TERMINAL` for terminal states.
- [ ] T078 [P] [US2] Create `{CHK}/fault/FaultInjector.java`:
  - An interface with `checkpoint(FaultPoint point)` and enum `FaultPoint { AFTER_FREEZE, AFTER_RESERVE_RESPONSE, AFTER_PREPARE, AFTER_COMMIT_RESPONSE }`.
  - `NoopFaultInjector` (`@Profile("!fault-injection")`).
  - `HaltingFaultInjector` (`@Profile("fault-injection")`). It holds an in-memory armed point that is cleared on restart, so a restarted process does not halt again, and it calls `Runtime.getRuntime().halt(137)` when the armed point is reached.
  - `{CHK}/fault/FaultController.java` (`@Profile("fault-injection")`) with `POST /internal/v1/ops/faults/{point}` to arm a point and `DELETE` to disarm it.
  - Create `{IT}/support/Faults.java`, which calls that endpoint and wraps the Toxiproxy API through `eu.rekawek.toxiproxy:toxiproxy-java` (as pulled by Testcontainers toxiproxy) at `127.0.0.1:8474`.
- [ ] T079 [US2] Call `faultInjector.checkpoint(...)` in `{CHK}/completion/CompletionService.java` right after the freeze commit (`AFTER_FREEZE`), and in `{CHK}/completion/AttemptDriver.java` after the reserve response arrives and before prepare (`AFTER_RESERVE_RESPONSE`), after the prepare commit (`AFTER_PREPARE`), and after the commit response arrives and before finalize (`AFTER_COMMIT_RESPONSE`).
- [ ] T080 [P] [US2] Create `{DEP}/docker-compose.faults.yml`, an override file that implements the quickstart's `faults` profile:
  - It adds a `toxiproxy` service (`ghcr.io/shopify/toxiproxy:2.9.0`, API on `127.0.0.1:8474`) with the proxy `inventory`, listening on `0.0.0.0:18083` with upstream `inventory:8083`. The proxy is created by a startup command or `{DEP}/toxiproxy/config.json`.
  - It sets `checkout`'s `INVENTORY_URL=http://toxiproxy:18083`, `SPRING_PROFILES_ACTIVE=fault-injection`, and a pass-through `CHECKOUT_RECOVERY_CANCEL_AFTER: ${CHECKOUT_RECOVERY_CANCEL_AFTER:-30s}`.
  - Make `Stack` (T040) include this override when the system property `-Dstack.faults=true` is set.
- [ ] T081 [US2] Add graceful shutdown to Checkout. Create `{CHK}/recovery/RecoveryLifecycle.java`, a `SmartLifecycle` that stops `RecoveryWorker` from picking new attempts on shutdown, waits up to 15 s for in-flight steps (each step ends in a durable state), and then releases this owner's leases with `UPDATE completion_attempts SET lease_until = NULL WHERE lease_owner = ?`.

**Checkpoint**: Completion is crash-safe and timeout-safe. T063–T070 pass, and no unexplained
attempt or reservation remains within 60 s of recovery.

---

## Phase 6: User Story 3 - Deploy Services Without Shared Database Access (Priority: P2)

**Goal**: Each service starts, migrates, becomes ready and redeploys on its own. Cross-store
access is denied, and the gateway is transport-only (FR-001–FR-005, FR-027, FR-028, SC-002).

**Independent Test**: quickstart V1–V3 (`IndependentStartupTest`, `PrivateStoreAccessTest`,
`ModuleBoundaryTest`, `GatewayUnavailableTest`) plus the provider and consumer contract tests.

### Tests for User Story 3 ⚠️

- [ ] T082 [P] [US3] Create `{IT}/boundaries/PrivateStoreAccessTest.java`:
  - For each service account (`catalog_service`, `checkout_service`, `inventory_service`, `analytics_service`) and each **other** instance port (3311–3314), assert that the JDBC connection fails with an access-denied or authentication error (SC-002).
  - On each owner's own instance, assert that `SHOW DATABASES` lists only its own database plus `information_schema` and `performance_schema`.
  - Assert that none of the former cross-store objects exists on any instance: `active_items`, `low_stock`, `published_popularity`, procedure `fulfill_checkout`, and a **view** named `accepted_scan_outbox`.
  - Delete `{IT}/boundaries/DatabasePermissionTest.java`.
- [ ] T083 [P] [US3] Create `{IT}/boundaries/ModuleBoundaryTest.java`, a static check run from the repository root. It asserts that:
  - (a) each domain module's `pom.xml` depends on no other domain module, only on `service-contracts`;
  - (b) no `.java` or `.sql` file under a service's `src/main` contains another service's schema-qualified name (regex `\b(catalog|checkout|inventory|analytics)\.[a-z_]+\b`, excluding its own schema and Java package names);
  - (c) `{DS}/service-contracts/src/main` imports nothing from `java.sql`, `javax.sql`, `org.springframework.jdbc` or `org.springframework.stereotype.Repository`;
  - (d) `{DS}/api-gateway/pom.xml` declares no `jdbc`, `flyway`, `mysql` or `kafka` artifact;
  - (e) `{GW}` contains only `GatewayApplication.java`, `UpstreamProxy.java`, `CheckoutRouteHandler.java` and `ReadModelRouteHandler.java`, plus classes whose names end in `Validation` or `Config`.
  Delete `{IT}/boundaries/NoDirectDomainCallsTest.java` and `{IT}/boundaries/GatewayIsolationTest.java` (R15).
- [ ] T084 [P] [US3] Rewrite `{IT}/deployment/IndependentStartupTest.java` (quickstart V1, US3-1, US3-3):
  1. Stop all application containers. For each service X, start only `X-db` and `X`, then assert `/actuator/health/readiness` is `UP`. For Checkout, the health details report `inventory: DOWN` and `catalogProjection: EMPTY` (and `kafka: DOWN` once US4 lands). Then stop X.
  2. Start everything, record each container's `State.StartedAt` (`docker inspect`), and run `docker compose up -d --build --no-deps inventory`. Assert that no other container's `StartedAt` changed, and that a scan and complete through the gateway still succeed.
- [ ] T085 [P] [US3] Create `{IT}/boundaries/GatewayUnavailableTest.java` (quickstart V3, A6). Stop `checkout` and assert that `POST /transactions` returns `503` with body `error == "SERVICE_UNAVAILABLE"` and only the fields `error` and `message`. Stop `catalog` and assert `GET /items` returns `503`. Restart both and wait for readiness.
- [ ] T086 [P] [US3] Create the provider contract tests (R16):
  - `{CAT_TEST}/internal/CatalogInternalContractTest.java` (`@WebMvcTest` with a mocked `CatalogRepository`): each internal endpoint's JSON deserializes into the `service-contracts` record, and every field named in `{SC_FIX}/catalog-*.json` and `product-*.json` is present.
  - `{INV_TEST}/reservation/ReservationContractTest.java`: the same check against `reservation-v1.json` and the 409 `InternalErrorV1` shape.
  Add `service-contracts` with `<classifier>tests</classifier>` or copy the fixtures through `maven-resources-plugin`, so provider tests can read `{SC_FIX}`.
- [ ] T087 [P] [US3] Create the consumer contract tests: `{CHK_TEST}/catalog/CatalogClientContractTest.java` and `{CHK_TEST}/inventory/InventoryClientContractTest.java`. Each serves the fixture JSON, with an extra unknown field added, from a local `HttpServer`, and asserts that the client parses it. It also asserts that a refused connection maps to `Unavailable` or `InventoryUnavailableException`, never `NotFound` (FR-029).

### Implementation for User Story 3

- [ ] T088 [US3] Create `{CHK}/health/InventoryHealthIndicator.java` and `{CHK}/health/CatalogProjectionHealthIndicator.java`:
  - `InventoryHealthIndicator` calls `GET {INVENTORY_URL}/actuator/health/readiness` with a 300 ms timeout and reports `UP` or `DOWN`.
  - `CatalogProjectionHealthIndicator` always reports status `UP`, with details `state=CURRENT|STALE|EMPTY`, `catalogVersion` and `lastVerifiedAt`, so a stale projection never fails readiness.
  - Register both under the names `inventory` and `catalogProjection`, outside the `readiness` group, which stays `readinessState,db`.
- [ ] T089 [US3] Verify that `{DEP}/docker-compose.yml` gives every application `depends_on` only on its own database and the gateway none. Run `{DEP}/scripts/reset.sh`, then T082–T085, and fix any coupling found, such as a startup call to another service that blocks readiness.

**Checkpoint**: Gates 1 and 2 pass. Each service deploys alone, and every cross-store access
attempt is denied.

---

## Phase 7: User Story 4 - View Reliable Catalog, Stock, and Popularity Results (Priority: P2)

**Goal**: Accepted scans flow through a transactional outbox, the relay and Kafka to an
idempotent Analytics pipeline. Analytics publishes complete snapshots. Low-stock and popular
items show catalog names and physical stock, and Analytics outages never block checkout
(FR-020–FR-026).

**Independent Test**: quickstart V8 1–6, plus `LowStockViewTest`. Reconciliation R-6 passes
after the backlog drains.

### Tests for User Story 4 ⚠️ (write first and confirm they fail)

- [ ] T090 [P] [US4] Replace `{ANA_TEST}/AnalyticsPipelineTest.java` with `{ANA_TEST}/window/WindowFilterTest.java`, a unit test. Cover:
  - boundaries are crossed at sequences 1, 500, 1000 and 1500;
  - the window for end `e` is `[max(1, e − 999), e]`, so `[1,1]`, `[1,500]`, `[1,1000]` and `[501,1500]`;
  - one batch crossing two boundaries publishes both;
  - ranking is by `scan_count DESC, sku ASC`;
  - a scan contributes once to every window containing it.
- [ ] T091 [P] [US4] Create `{ANA_TEST}/ingest/ScanIngestIT.java` (Testcontainers `KafkaContainer` and `MySQLContainer`):
  - duplicates of one `eventId` give a single `ingested_scans` row, and its `sequence_number` never changes;
  - a value of `not-json` and an event with `schemaVersion: 2` land in `ingest_rejections` and are acknowledged;
  - when the listener's transaction fails mid-batch (an injected exception), no offset is committed and redelivery produces the same sequence numbers.
- [ ] T092 [P] [US4] Create `{CHK_TEST}/outbox/OutboxRelayIT.java` (Testcontainers Kafka and MySQL with Checkout's Flyway):
  - published rows get `published_at` only after the broker acknowledges them;
  - with Kafka stopped, rows stay pending and scans keep committing;
  - a row with a lower `outbox_id` committed after a higher one is still published on a later pass (no watermark skip);
  - when the process is killed between acknowledgment and marking (simulated by a test hook), the rows are republished with the same `eventId`;
  - with two relay instances, only one holds `GET_LOCK('checkout.scan-relay', 0)`.
- [ ] T093 [P] [US4] Create `{IT}/analytics/KnownSequenceRankingTest.java` (quickstart V8.1, SC-006). After `reset.sh`, send 1,500 scans **sequentially** through the gateway, cycling a fixed SKU pattern across transactions (completions not required). Then poll `GET /analytics/popular-items?limit=2000` until `windowEnd == 1500`. Assert `windowStart == 501` and that the counts and ranks equal those computed in the test from scans 501–1500, with SKU-ascending ties. Also assert the earlier publications at 1 and 500 through `ingest-status` history or the snapshot table read with the analytics owner's credentials.
- [ ] T094 [P] [US4] Create `{IT}/analytics/ReplayDuplicateTest.java` (quickstart V8.2):
  1. After T093's sequence, record the snapshot rows and `ingested_scans` count.
  2. Stop analytics and run `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 --group analytics-scan-ingest --topic checkout.accepted-scans.v1 --reset-offsets --to-earliest --execute`.
  3. Run `UPDATE accepted_scan_outbox SET published_at = NULL` with the checkout owner's credentials, then start analytics.
  4. After lag 0, assert identical `sequence_number`s, window boundaries, counts, ranks and `ingestedCount`.
- [ ] T095 [P] [US4] Create `{IT}/analytics/OutageRecoveryTest.java` (quickstart V8.3, SC-007):
  1. Stop analytics and accept 5,000 scans.
  2. Stop kafka and accept 5,000 more scans. Every scan returns `200`.
  3. Start kafka, then analytics, and accept 1,000 more scans.
  4. Wait until `consumerLag == 0` and `outboxPending == 0`.
  5. Assert `outboxTotal == ingestedCount == 11,000` and `Reconciliation.assertPass()`, including R-6.
- [ ] T096 [P] [US4] Create `{IT}/analytics/AnalyticsInterruptionTest.java` (quickstart V8.4, SC-010). Run 10 worker threads of start, scan (1–20) and complete through `PublicApi` for 90 s. Stop analytics at t=30 s and start it at t=60 s. Assert zero non-2xx responses for start, scan and complete, and that all scans are ingested after recovery. Delete `{IT}/checkout/AnalyticsOutageCheckoutTest.java`.
- [ ] T097 [P] [US4] Create `{IT}/analytics/PopularItemsViewTest.java` (quickstart V8.5–6, US4-5, US4-6):
  - After `reset.sh`, `GET /analytics/popular-items` returns `200` with `items: []`, `windowStart: 0` and `windowEnd: 0`.
  - While 4,000 scans are being published, 8 reader threads poll continuously, and every response's items belong to one `windowEnd`: counts sum to at most `windowEnd − windowStart + 1` and match the stored snapshot for that `windowEnd`.
  Delete `{IT}/analytics/AnalyticsIdempotencyTest.java`.
- [ ] T098 [P] [US4] Create `{IT}/inventory/LowStockViewTest.java` (US4-1, FR-026):
  - With `SKU-000020` set to 5, `GET /inventory/low-stock?threshold=10` lists it with `name` equal to the catalog name from `GET /items`, not the SKU, and `currentStock: 5`.
  - After `PUT http://127.0.0.1:8083/internal/v1/reservations/<uuid>` reserves 3, `currentStock` is still 5. Cancel that reservation afterwards.
  - After truncating `catalog_names` with inventory owner credentials and stopping `catalog`, the response is `503 SERVICE_UNAVAILABLE`. Restart catalog and wait for the reload.
- [ ] T099 [P] [US4] Create the event fixture tests `{CHK_TEST}/outbox/AcceptedScanSerializationTest.java` and `{ANA_TEST}/ingest/AcceptedScanParsingTest.java`:
  - Producer side: Checkout's event serializer output for a known outbox row equals `{SC_FIX}/accepted-scan-v1.json` field by field.
  - Consumer side: Analytics parses the fixture, and the fixture with an extra unknown field.

### Implementation for User Story 4: Checkout outbox and relay

- [ ] T100 [US4] Create `{CHK_DB}/V6__accepted_scan_outbox.sql`:
  - `DROP TABLE accepted_scan_events;`
  - Create `accepted_scan_outbox` with columns `outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY`, `event_id CHAR(36) NOT NULL UNIQUE`, `transaction_id VARCHAR(64) NOT NULL`, `station_id VARCHAR(255) NOT NULL`, `sku VARCHAR(64) NOT NULL`, `accepted_at TIMESTAMP(6) NOT NULL` and `published_at TIMESTAMP(6) NULL`, plus `INDEX idx_outbox_pending (published_at, outbox_id)`. Data-model rule: "Set only after broker acknowledgment".
- [ ] T101 [US4] Modify `{CHK}/scan/ScanService.java`. Replace the `accepted_scan_events` insert with `INSERT INTO accepted_scan_outbox (event_id, transaction_id, station_id, sku, accepted_at) VALUES (UuidV7.next(), ?, ?, ?, CURRENT_TIMESTAMP(6))` in the **same** local transaction as the basket upsert (FR-020). Rejected scans (404, 409 or 503) write nothing. Read `station_id` in the same `FOR SHARE` select.
- [ ] T102 [P] [US4] Create `{CHK}/outbox/KafkaProducerConfig.java` and add `spring-kafka` to `{DS}/checkout-service/pom.xml`:
  - **Producer**: bootstrap `${KAFKA_BOOTSTRAP}`, `enable.idempotence=true`, `acks=all`, `delivery.timeout.ms=10000`, String key and value serializers.
  - **Topic**: a `NewTopic` for `AcceptedScanV1.TOPIC` with 1 partition, replication 1, `retention.ms=604800000`, `cleanup.policy=delete` and `min.insync.replicas=1`.
  - **Admin**: `KafkaAdmin.setFatalIfBrokerNotAvailable(false)` and `setAutoCreate(true)`, so Checkout starts and becomes ready without Kafka (US3-1).
- [ ] T103 [US4] Create `{CHK}/outbox/AcceptedScanEventMapper.java`, which maps an outbox row to `AcceptedScanV1` JSON. Set key `transactionId` and the headers `eventId`, `eventType=AcceptedScan` and `schemaVersion=1`.
- [ ] T104 [US4] Create `{CHK}/outbox/OutboxRelay.java`, a single background thread started on `ApplicationReadyEvent`:
  - **Lock**: on a **dedicated** connection held for the relay's lifetime, run `SELECT GET_LOCK('checkout.scan-relay', 0)`. If the lock is not acquired, retry every 5 s.
  - **Loop**:
    1. `SELECT … FROM accepted_scan_outbox WHERE published_at IS NULL ORDER BY outbox_id LIMIT 500`.
    2. Send every row with `KafkaTemplate.send` and wait for all futures.
    3. Run `UPDATE accepted_scan_outbox SET published_at = CURRENT_TIMESTAMP(6) WHERE outbox_id IN (<acknowledged ids>)`.
    4. Loop immediately when a full batch was found; otherwise sleep `checkout.relay.poll-ms` (200 ms).
  - **Send failure**: back off from 200 ms doubling to 5 s, and leave the rows pending.
  - **Purge**: every 60 s, delete published rows older than `checkout.relay.retention` (24h) in batches of 1,000.
  - **Metric**: gauge `checkout.outbox.pending` (`COUNT(*) WHERE published_at IS NULL`, refreshed every 5 s).
  - **Shutdown**: a `SmartLifecycle` stop finishes the current batch, then `RELEASE_LOCK`.
- [ ] T105 [US4] Create `{CHK}/health/KafkaHealthIndicator.java` (`describeCluster` with a 500 ms timeout, reporting `UP`/`DOWN` as detail `kafka`, outside readiness) and `{CHK}/health/OutboxHealthIndicator.java` (detail `outboxPending`, status always `UP`).
- [ ] T106 [US4] Add `outboxPending` and `outboxTotal` (`COUNT(*)` over all outbox rows, published and pending) to the `GET /internal/v1/ops/reconciliation-export` response in `{CHK}/ops/OperationsController.java`. Document that `outboxTotal` assumes the 24 h purge has not run during the measured window.

### Implementation for User Story 4: Analytics

- [ ] T107 [US4] Create `{ANA_DB}/V4__ingest_and_snapshots.sql`:
  - `DROP TABLE processed_scans; DROP TABLE analytics_events;`
  - `ingested_scans`: `event_id CHAR(36) PRIMARY KEY`, `sequence_number BIGINT NOT NULL UNIQUE`, `sku VARCHAR(64) NOT NULL`, `accepted_at TIMESTAMP(6) NOT NULL` and `ingested_at TIMESTAMP(6) NOT NULL`. Data-model rule: "assigned on first ingest. Immutable".
  - `ingest_state`: `id TINYINT PRIMARY KEY CHECK (id = 1)` and `last_sequence BIGINT NOT NULL`, with the row `(1, 0)`.
  - `DELETE FROM popularity_snapshot_items; DELETE FROM popularity_snapshots;`
  - `ALTER TABLE popularity_snapshot_items ADD COLUMN name VARCHAR(255) NOT NULL`.
  - `published_snapshot`: `id TINYINT PRIMARY KEY CHECK (id = 1)` and `current_window_end BIGINT NULL`, with the row `(1, NULL)`. Data-model rule: "NULL ⇒ empty result".
  - `ingest_rejections`: `kafka_offset BIGINT PRIMARY KEY`, `reason VARCHAR(255) NOT NULL`, `raw_value MEDIUMTEXT` and `rejected_at TIMESTAMP(6) NOT NULL`.
  - `catalog_names`: `sku VARCHAR(64) PRIMARY KEY`, `name VARCHAR(255) NOT NULL` and `catalog_version BIGINT NOT NULL`.
  - `projection_state`: `id TINYINT PRIMARY KEY CHECK (id = 1)`, `catalog_version BIGINT NULL`, `loaded_at TIMESTAMP(6) NULL` and `last_verified_at TIMESTAMP(6) NULL`, with the row `(1, NULL, NULL, NULL)`.
- [ ] T108 [P] [US4] Create Analytics' name projection: `{ANA}/catalog/CatalogClient.java`, `{ANA}/catalog/CatalogNameProjection.java` and `{ANA}/catalog/CatalogNameProjectionLoader.java`. They follow the T032–T034 pattern (version probe every 15 s, paged load at a single version, atomic replace in one local transaction, `EMPTY`/`CURRENT`/`STALE` states) but store names only (R7). Add a `service-contracts` dependency to `{DS}/analytics-service/pom.xml`, and add `@EnableScheduling`.
- [ ] T109 [US4] Create the three pipeline filters, each one class with one responsibility (constitution III):
  - `{ANA}/ingest/IngestFilter.java`:
    - Within the caller's transaction, `SELECT last_sequence FROM ingest_state WHERE id = 1 FOR UPDATE`.
    - For each record in partition order, `INSERT IGNORE INTO ingested_scans`. When a row is inserted, assign `last_sequence + 1`.
    - Update `ingest_state`.
    - Return the new sequence range.
  - `{ANA}/window/WindowFilter.java`: given the previous and new `last_sequence`, emit every crossed boundary (1, then multiples of 500) with its window `[max(1, end − 999), end]`.
  - `{ANA}/rank/RankAndPublishFilter.java`: for each window, run `SELECT sku, COUNT(*) FROM ingested_scans WHERE sequence_number BETWEEN ? AND ? GROUP BY sku ORDER BY COUNT(*) DESC, sku ASC`, insert `popularity_snapshots` and items (with `name` from `CatalogNameProjection`), then run `UPDATE published_snapshot SET current_window_end = ?`.
  - `{ANA}/ingest/ScanPipeline.java` connects only adjacent filters, in the order `ingest → window → rank-and-publish`.
  - If a name is missing at publication, throw. The batch rolls back and is redelivered after the projection loads, so it is never published with a SKU as the name.
- [ ] T110 [US4] Create `{ANA}/ingest/AcceptedScanListener.java` and `{ANA}/ingest/KafkaConsumerConfig.java`, and add `spring-kafka` to the analytics pom:
  - **Consumer**: group `analytics-scan-ingest`, `enable.auto.commit=false`, `auto.offset.reset=earliest`, `max.poll.records=500`, and a batch listener on `AcceptedScanV1.TOPIC` with `AckMode.MANUAL`.
  - **Per batch**: parse each record. Write unparseable values or `schemaVersion > 1` to `ingest_rejections` and increment `analytics.ingest.rejected`. Run `ScanPipeline` for the valid records in **one** `TransactionTemplate`, then `ack.acknowledge()` after commit. On exception, do not acknowledge; the error handler seeks back for redelivery.
  - **Shutdown**: on graceful shutdown, stop the container after the in-flight batch.
  - Delete the legacy polling and publication code from `{ANA}/AnalyticsApplication.java`.
- [ ] T111 [US4] Create `{ANA}/api/PopularItemsController.java`. `GET /analytics/popular-items?limit=` runs in one read-only transaction: read `published_snapshot.current_window_end`. If it is NULL, return `items: []`, `windowStart: 0` and `windowEnd: 0`. Otherwise read that snapshot's header and its items ordered by `rank_position` with `LIMIT`. The response follows the OpenAPI `PopularItemsResponse` shape exactly. `limit < 1` returns `400 INVALID_LIMIT`.
- [ ] T112 [US4] Create `{ANA}/ops/IngestStatusController.java` and `{ANA}/health/AnalyticsHealthIndicators.java`:
  - `GET /internal/v1/ops/ingest-status` returns `ingestedCount`, `lastSequence`, `currentWindowEnd`, `consumerLag` and `rejected`. `consumerLag` is the end offset minus the committed offset for the group, read through `AdminClient` with a 1 s timeout, or `null` if Kafka is unreachable.
  - The health indicators add the details `kafkaConsumer` and `catalogProjection` and stay outside readiness.

### Implementation for User Story 4: Inventory low-stock view

- [ ] T113 [US4] Create `{INV_DB}/V7__catalog_names.sql` (`catalog_names` and `projection_state`, the same definitions as in T107) and the Inventory name projection `{INV}/catalog/CatalogClient.java`, `{INV}/catalog/CatalogNameProjection.java` and `{INV}/catalog/CatalogNameProjectionLoader.java`, following the T108 pattern. Add `@EnableScheduling` and `{INV}/health/CatalogProjectionHealthIndicator.java` (detail only).
- [ ] T114 [US4] Create `{INV}/lowstock/LowStockController.java` and `{INV}/lowstock/LowStockRepository.java`. `GET /inventory/low-stock?threshold=` (default `${LOW_STOCK_THRESHOLD:10}`):
  - selects `sku` and `physical_stock` where `physical_stock < threshold`;
  - sets `name` from `CatalogNameProjection`; if any alerted SKU has no name, the response is `503 SERVICE_UNAVAILABLE`;
  - sets `currentStock = physical_stock`;
  - sets `triggeredAt` to the `occurred_at` of the most recent `SALE` movement whose `physical_after < threshold`, or the response time when there is none.
  The response follows the OpenAPI `LowStockResponse` exactly. Delete the legacy low-stock code from `{INV}/InventoryApplication.java`.
- [ ] T115 [US4] Extend `{DEP}/scripts/reconcile.sh` with check R-6. It reads `http://127.0.0.1:8084/internal/v1/ops/ingest-status`, waits under `--wait-settled` until `outboxPending == 0` and `consumerLag == 0`, then asserts `outboxTotal == ingestedCount`. When Analytics is unreachable, R-6 prints `SKIPPED (analytics unreachable)` and the overall result is `RECONCILIATION FAIL`, never a silent pass.

**Checkpoint**: Gate 6 passes. T090–T099 pass, and popular items, low stock and catalog views
show correct names and physical stock.

---

## Phase 8: User Story 5 - Verify Performance Improvements With Comparable Evidence (Priority: P3)

**Goal**: Measure the redesign under the same host, workload and stock seed as the Phase 2
baseline. Publish `COMPARISON.md` stating each SC-009 target as met or unmet (FR-031, SC-008,
SC-009).

**Independent Test**: quickstart V10. `reports/redesign/` holds 3 normal and 3 stress reports
with observations and passing reconciliation, and `COMPARISON.md` covers all of them,
failures included.

- [ ] T116 [P] [US5] Create `{DEP}/provisioning/measured-stock.sql` for the **redesign schema**. In one transaction, it first inserts a `PROVISION` movement per SKU (`quantity_delta = 1000000 − physical_stock`, `physical_after = 1000000`, `attempt_id NULL`), then runs `UPDATE inventory_positions SET initial_stock = initial_stock + (1000000 − physical_stock), physical_stock = 1000000`. The inserts come first so they read the pre-update stock. Header comment: run with the inventory owner credentials after reset.
- [ ] T117 [P] [US5] Create `{DEP}/scripts/compare-reports.sh` (bash and `jq`):
  - **Input**: the reports in `{DEP}/reports/baseline/` and `{DEP}/reports/redesign/` (`report-*.json`), using the fields `config.stations`, `config.durationSecondsRequested`, `wallClockSeconds`, and `operations[].{operation, successCount, errorCount, p95Ms, p99Ms, meanMs}`.
  - **Classification**: a run is stress when `config.stations == 200`.
  - **Medians per topology**: the median of `SCAN_ITEM.p95Ms` across the 3 stress runs, successful-scan throughput (`SCAN_ITEM.successCount / wallClockSeconds`), and the start and complete p95.
  - **Output**: `{DEP}/reports/COMPARISON.md` with:
    - a per-run table that includes errors;
    - a medians table;
    - SC-009 verdicts: scan p95 `redesign ≤ 0.90 × baseline`, throughput `redesign ≥ baseline`, start and complete p95 `redesign ≤ baseline`, each printed **MET** or **UNMET**;
    - a "Declared differences" section listing host, topology (1 vs 4 MySQL instances plus Kafka), pool sizes, seed, timeouts and commit SHAs, taken from the observation files;
    - a "Known baseline functional defects" subsection inside "Declared differences". It is a fixed table that the script writes verbatim, with columns *Finding*, *Baseline behavior*, *Redesign behavior* and *Effect on measured runs*. Its rows come from `specs/004-domain-services/research.md` "Findings from the current implementation":
      - **F1**: Analytics re-reads the same first 500 `checkout.accepted_scan_outbox` rows every `analytics.poll-ms` (1 s) and never advances. The baseline does almost no analytics work but sends about 500 no-op `INSERT IGNORE` statements per second to the shared instance. The redesign ingests every scan through the outbox, relay and Kafka. *Effect*: the analytics load on the database differs in both directions, so analytics cost is not comparable.
      - **F2**: sequence numbers are assigned with `MAX()+1` outside a lock, versus a locked `ingest_state`. *Effect*: negligible on measured runs, because F1 stops ingestion after 500 scans.
      - **F3**: snapshots are deleted and reinserted for the same `window_end`, versus new rows plus a pointer flip. *Effect*: popular-items results can be partial in the baseline. The latency effect is negligible.
      - **F4**: low-stock and popular items return the SKU as `name`, versus projected catalog names. *Effect*: none on latency; the read-view correctness differs.
      - **F5**: the gateway turns every upstream 4xx into a 500, versus pass-through with typed `ApiError` codes. *Effect*: none with adequate stock, where no 4xx is expected. Any baseline 500s must be examined for masked 4xx before they are compared.
      - **F6**: completion runs as one Checkout transaction that calls the `inventory.fulfill_checkout` procedure, with one cumulative receipt row per transaction, versus the saga with per-attempt receipts. *Effect*: the architectural change under test; complete-latency differences are attributable to it.
      - **F9**: Checkout uses a Hikari pool of 10 with up to 8 scan deadlock retries, versus the pool size chosen in T118. *Effect*: a configuration difference, quantified from the `hikaricp.*` and `checkout.db.retries` observations.

      F7 (shared instance) is covered by the topology row. F8 (inadequate stock) is removed for both topologies by the 1,000,000-unit provisioning. F10 and F11 have no runtime effect, so they are listed once with "no runtime effect". If any baseline observation contradicts a row's *Effect* text, T120 corrects that row by hand.
  - Never drop failed requests or runs.
- [ ] T118 [US5] Choose Checkout's `DB_POOL_SIZE` from the baseline's `hikaricp.connections.pending` and acquire-time evidence (T013) plus one exploratory redesign stress run. Record the chosen value and its evidence in `{DEP}/reports/redesign/POOL-SIZING.md` and set it as the default in `{DEP}/docker-compose.yml`. Exploratory runs go to `{DEP}/reports/redesign/exploratory/` and are not counted in the comparison.
- [ ] T119 [US5] Run `{DEP}/scripts/run-measurement.sh --topology redesign --compose-file {DEP}/docker-compose.yml --stock-sql {DEP}/provisioning/measured-stock.sql --report-dir {DEP}/reports/redesign`, 3 times each with `--profile normal` and `--profile stress`. Do not enable the fault-injection profile. Each run is followed by `reconcile.sh --wait-settled 60`. Commit all reports, observations and reconciliation outputs. Normal runs must have zero errors (SC-008); explain any non-zero errors.
- [ ] T120 [US5] Run `{DEP}/scripts/compare-reports.sh` and add an "Analysis" section to `{DEP}/reports/COMPARISON.md`. It compares the baseline and redesign bottleneck observations (pool wait, lock waits and deadlocks, statement latency, CPU) and attributes changes only where the evidence supports them. For each SC-009 metric, the analysis also says whether any defect in the "Known baseline functional defects" table (F1–F6, F9) could account for part of the difference, using the baseline observations to support or rule out the claim. For example, it compares the share of statement time spent on the F1 analytics no-op statements against the scan path's database time. A gain that a defect fix could explain is attributed to that fix, not to the microservice architecture (constitution V). Rows whose *Effect* text the evidence contradicts are corrected in `COMPARISON.md`. An unmet target is reported as **UNMET** with the likely cause, never inferred from the architecture (SC-009).

**Checkpoint**: Gate 4 passes, or the unmet targets are reported truthfully.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Public contract conformance across all seven operations, the documentation entry
point, cleanup and full validation.

- [ ] T121 [P] Rewrite `{IT}/contract/FullGatewayContractTest.java` (quickstart V4, gate 3, SC-001):
  - Validate every response of the seven operations against `spec/self-checkout-openapi.yaml` v1.2.0, using `com.atlassian.oai:swagger-request-validator-core` (add the latest release on Maven Central to the integration-tests pom).
  - Trigger each error code in the error catalog of `public-api-amendment.md` and assert its status. The 503 cases come from stopped services.
  - Assert `Transaction.status` values are only `OPEN`, `COMPLETED` or `CANCELLED`.
  - Assert low-stock and popular-items `name` values equal the catalog names.
- [ ] T122 [P] Rewrite `{DEP}/ARCHITECTURE.md` for the private-store design:
  - deployables and ports;
  - an ownership and permission table (instance, database, account, and the absence of cross grants);
  - internal contracts, linking to `specs/004-domain-services/contracts/`;
  - the saga state machine and recovery rules (link to `data-model.md`);
  - the outbox, relay and Kafka path;
  - the Analytics `ingest → window → rank-and-publish` order;
  - the catalog projection policy.
  Add the superseded note pointing to `public-api-amendment.md` (R19).
- [ ] T123 Rewrite `{DEP}/README.md` as the single documentation entry point (FR-033, SC-011). Within its first screen, it links to: deployments and ports; the ownership and permission table in `ARCHITECTURE.md`; the internal contracts; the saga and recovery rules; the analytics flow; `reports/baseline/`, `reports/redesign/` and `reports/COMPARISON.md`; reconciliation outputs and `scripts/reconcile.sh`; and the quickstart. It states the prerequisites (JDK 21, Maven 3.9, Docker Compose v2, bash, `jq`). Link it from the root `README.md`.
- [ ] T124 [P] Update `specs/004-domain-services/quickstart.md` to match the implementation:
  - the `faults` profile is the override file `docker-compose.faults.yml`;
  - `jq` is a V0 prerequisite;
  - ops ports `127.0.0.1:8081–8084` are operator-only;
  - `run-measurement.sh` is used for V10.
  Change no expected outcomes.
- [ ] T125 [P] Remove obsolete artifacts:
  - delete `{DS}/integration-tests/scripts/reconcile-inventory.sql`, whose baseline copy remains at tag `baseline/service-based-v1`, along with its references;
  - delete `{CHK}/CheckoutService.java` and `{CHK}/CheckoutRepository.java` if nothing references them any more;
  - delete any unused imports or classes in `{INV}/InventoryApplication.java` and `{ANA}/AnalyticsApplication.java`.
  Confirm that `mvn -q -f domain-services/pom.xml -DskipTests package` succeeds.
- [ ] T126 Run the full quickstart validation V0–V11 from a `reset.sh` state, including `mvn -f domain-services/pom.xml verify` with the stack running and with `-Dstack.faults=true` for the V7 tests. Save each scenario's output under `{DEP}/reports/validation-<UTC date>/`, and link the folder from `{DEP}/README.md`. Any failure is recorded and fixed, never omitted.
- [ ] T127 (Optional; requires the user's explicit approval before editing) Propose the PATCH clarification of constitution principle IV in `.specify/memory/constitution.md`: "identify unavailable quantities through the retained basket". Bump the version to 3.0.1 and update the amendment date, per plan Complexity Tracking. Skip this task if it is not approved.

---

## Dependencies & Execution Order

### Phase Dependencies

| Phase | Depends on | Notes |
|---|---|---|
| 1 Setup | — | T001 must precede every code change (FR-034) |
| 2 Baseline (US5 part 1) | T001 | **Blocks Phase 3.** T012 needs T008–T011. T013 needs T012 |
| 3 Foundational | Phase 2 | Blocks all story phases |
| 4 US1 (P1, MVP) | Phase 3 | — |
| 5 US2 (P1) | Phase 4 | Extends US1's driver, freeze and Inventory reservations |
| 6 US3 (P2) | Phase 3 | Can run in parallel with Phases 4–5. T084's Kafka detail check activates after T105 |
| 7 US4 (P2) | Phase 3 | Can run in parallel with Phases 4–6. T101 edits the `ScanService.java` file that T056 also edits, so sequence those two. T115 extends T062 |
| 8 US5 part 2 (P3) | Phases 4–7 | Measures the complete redesign |
| 9 Polish | Phases 4–8 | T126 is last |

### User Story Dependencies

- **US1**: needs Foundational only. It delivers the MVP: correct partial purchases.
- **US2**: needs US1, because it adds deadline, join, cancel and recovery to the same attempt
  driver. Its tests use US1's reconciliation script.
- **US3**: needs Foundational only. Its boundary tests become stronger as later stories add
  modules, so re-run them in T126.
- **US4**: needs Foundational only for its scan path. The outbox edit in `ScanService.java` must
  be applied after T056 if US1 runs concurrently.
- **US5**: the baseline (Phase 2) is a prerequisite of everything. The comparison (Phase 8)
  needs all stories.

### Within Each User Story

- Write the tests first and confirm they fail, then migrations, repositories, services or
  drivers, controllers, and wiring.
- A migration comes before any code that reads its tables.
- Story checkpoint tests must pass before the phase is marked complete.

### Parallel Opportunities

- **Phase 1**: T002–T005 run together after T001.
- **Phase 2**: T009, T010 and T011 run together, while T006–T008 proceed.
- **Phase 3**:
  - T015, T016 and T017 run in parallel once T014 is done, followed by T018.
  - T020 and T021 run together.
  - The error-mapping tasks T023–T026 run together.
  - The migrations T027–T030 run together.
  - T032 runs in parallel with T031.
- **US1**: tests T041–T045 run together, and so do migrations T046/T047. T051–T053 run in
  parallel with T048–T050.
- **US2**: tests T063–T070 run together. T078 and T080 run in parallel with T071–T077.
- **US3**: T082–T087 all run in parallel.
- **US4**: tests T090–T099 run together. Checkout outbox work (T100–T106), the Analytics
  pipeline (T107–T112) and Inventory low-stock work (T113–T114) are three independent tracks.
- **US5 part 2**: T116 and T117 run together.
- **Polish**: T121, T122, T124 and T125 run together.

---

## Parallel Example: User Story 1

```bash
# Tests first (all different files):
Task: "T041 ReservationServiceIT in domain-services/inventory-service/src/test/java/edu/northeastern/cs6510/inventory/reservation/ReservationServiceIT.java"
Task: "T042 AttemptDriverTest in domain-services/checkout-service/src/test/java/edu/northeastern/cs6510/checkout/completion/AttemptDriverTest.java"
Task: "T043 PartialFulfillmentTest in domain-services/integration-tests/src/test/java/edu/northeastern/cs6510/completion/PartialFulfillmentTest.java"
Task: "T044 CompletionPreconditionTest in domain-services/integration-tests/src/test/java/edu/northeastern/cs6510/completion/CompletionPreconditionTest.java"
Task: "T045 ConcurrentLastUnitTest in domain-services/integration-tests/src/test/java/edu/northeastern/cs6510/completion/ConcurrentLastUnitTest.java"

# Migrations in two different services:
Task: "T046 V6__reservations.sql in domain-services/inventory-service/src/main/resources/db/migration/"
Task: "T047 V5__completion_attempts.sql in domain-services/checkout-service/src/main/resources/db/migration/"

# Independent Checkout helpers alongside Inventory work:
Task: "T052 InventoryClient.java"   Task: "T053 UuidV7.java"   Task: "T051 InventoryOpsController.java"
```

## Parallel Example: User Story 4

```bash
# Three independent implementation tracks after the tests:
Track A (Checkout):  T100 → T101 → T102/T103 → T104 → T105 → T106
Track B (Analytics): T107 → T108 → T109 → T110 → T111 → T112
Track C (Inventory): T113 → T114
Then: T115 (reconcile R-6)
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1: amend the contract and clean up the repository.
2. Phase 2: record the baseline. This is mandatory before code changes.
3. Phase 3: foundational private stores, contracts, projection scan path and gateway.
4. Phase 4: US1. **Stop and validate** with T043–T045 and `reconcile.sh`.

### Incremental Delivery

1. US1 → correct partial purchases (MVP).
2. US2 → crash, timeout and duplicate safety. Gate 5 is complete after this.
3. US3 → proven independent deployment and boundaries (gates 1–2).
4. US4 → durable analytics and correct read views (gate 6).
5. US5 → redesign measurements and `COMPARISON.md` (gate 4).
6. Polish → contract conformance (gate 3), documentation (gate 7) and full V0–V11 validation.

### Parallel Team Strategy

After Phase 3, developer A takes US1 then US2, developer B takes US3, and developer C takes US4,
coordinating on `ScanService.java` (T056 before T101). Everyone joins for US5 measurement and
Polish.

---

## Notes

- `[P]` means a different file and no dependency on an incomplete task. `[USn]` maps to
  spec.md's user stories.
- Commit after each task or logical group. Never commit `domain-services/deployment/services.env`.
- Measured runs (T012, T119) never use the `fault-injection` profile or the faults override.
- Never edit `load-client/` or `mockserver/` (constitution II).
- Historical reports stay labeled. They are never presented as baseline or redesign evidence
  (FR-033).
