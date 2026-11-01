---

description: "Task list for 005 event-driven communication between checkout microservices"
---

# Tasks: Event-Driven Communication Between Checkout Microservices

**Input**: Design documents from `specs/005-event-driven-services/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/README.md), [quickstart.md](quickstart.md)

**Tests**: Included. The spec requires an automated boundary check (FR-022) and named validation
scenarios (FR-028), and constitution gates 2, 5, and 6 require automated evidence.

**Organization**: Tasks are grouped by user story so each story can be implemented and tested on
its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: The user story the task belongs to (US1–US5)

## Path Conventions

The repository is a Maven reactor under `domain-services/`. Abbreviations used in task
descriptions (always expand them to the full path):

| Short | Full path |
|---|---|
| `SC/` | `domain-services/service-contracts/src/main/java/edu/northeastern/cs6510/contracts/` |
| `SCT/` | `domain-services/service-contracts/src/test/` |
| `CAT/` | `domain-services/catalog-service/src/main/java/edu/northeastern/cs6510/catalog/` |
| `CO/` | `domain-services/checkout-service/src/main/java/edu/northeastern/cs6510/checkout/` |
| `COT/` | `domain-services/checkout-service/src/test/java/edu/northeastern/cs6510/checkout/` |
| `INV/` | `domain-services/inventory-service/src/main/java/edu/northeastern/cs6510/inventory/` |
| `INVT/` | `domain-services/inventory-service/src/test/java/edu/northeastern/cs6510/inventory/` |
| `AN/` | `domain-services/analytics-service/src/main/java/edu/northeastern/cs6510/analytics/` |
| `IT/` | `domain-services/integration-tests/src/test/java/edu/northeastern/cs6510/` |
| `DEP/` | `domain-services/deployment/` |

Build with JDK 21 (`JAVA_HOME="/c/Program Files/Java/jdk-21"` on this machine; the default
`java` is 17).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Fix the comparison point, add missing dependencies, and prepare measurement tooling.

- [x] T001 Create the annotated Git tag `pre-event-driven` on the current `HEAD` of branch `event-based` (the 004 build plus the two typo fixes) and record its commit hash in `DEP/reports/event-driven/README.md` (new file: one paragraph that explains the folder layout `baseline-004/`, `default/`, `stress/`, `NOTES.md`, `COMPARISON.md`)
- [x] T002 [P] Add `org.springframework.kafka:spring-kafka` (compile) and `org.testcontainers:kafka` (test) to `domain-services/catalog-service/pom.xml` and `domain-services/inventory-service/pom.xml`, matching how `domain-services/checkout-service/pom.xml` declares them (versions are managed by the parent)
- [x] T003 [P] Extend `DEP/scripts/run-measurement.sh`. Add `--profile default`, which passes no `--stations` or `--duration` flags so the client runs its built-in 10 × 60 s, exactly `java -cp out Main`. Add `--profile stress100`, which passes exactly `--stations=100 --duration=120`. Accept `--topology pre-event-driven|event-driven` alongside the existing `baseline|redesign`. Both new topologies take the existing `redesign` path: no `grant-cross-domain-interfaces.sh`, and reconciliation through `domain-services/deployment/scripts/reconcile.sh` from the tree under test (`$tree`). Keep the existing `normal`/`stress` profiles unchanged
- [ ] T004 Run the 004 comparison at the tag. `git worktree add ../cs6510-pre-event-driven pre-event-driven`, then from this working tree run `DEP/scripts/run-measurement.sh --topology pre-event-driven --compose-file ../cs6510-pre-event-driven/domain-services/deployment/docker-compose.yml --stock-sql DEP/provisioning/measured-stock.sql --profile default --report-dir DEP/reports/event-driven/baseline-004/default`, and again with `--profile stress100` and `--report-dir DEP/reports/event-driven/baseline-004/stress` (3 runs each). Commit the `report-*.json`, `observations-*.json`, and `reconciliation-*.txt` files (depends on T001, T003)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Message contracts, envelope, and per-service Kafka plumbing that every story uses.

**⚠️ CRITICAL**: No user-story work can begin until this phase is complete.

- [x] T005 Create `SC/messaging/MessageHeaders.java`: string constants `messageId`, `messageType`, `schemaVersion`, `producer`, `occurredAt`, `correlationId`, `causationId`, plus the dead-letter headers `dlt-original-topic`, `dlt-original-partition`, `dlt-original-offset`, `dlt-exception-class`, and `dlt-exception-message` ([contracts/README.md](contracts/README.md) "Envelope"). It contains no Spring or Kafka types
- [x] T006 Create `SC/messaging/Envelope.java`: an immutable record `(String messageId, String messageType, int schemaVersion, String producer, Instant occurredAt, String correlationId, String causationId)`, a `toHeaders()` method returning `Map<String, byte[]>` (UTF-8), and a static `fromHeaders(Map<String, byte[]>)` that throws `IllegalArgumentException` when `messageId`, `messageType`, `schemaVersion`, `producer`, or `occurredAt` is missing (depends on T005)
- [x] T007 [P] Create `SC/catalog/v1/ProductEventV1.java` per [contracts/catalog-product-event.md](contracts/catalog-product-event.md), with `@JsonIgnoreProperties(ignoreUnknown = true)` and fields `schemaVersion, messageId, messageType, catalogVersion, sku, name, priceCents, active`. Add a `validate()` method that rejects `catalogVersion < 1`, a blank `name` or one longer than 255 characters, and `priceCents < 0`
- [x] T008 [P] Create `SC/inventory/v1/InventoryCommandV1.java` per [contracts/inventory-reservation-messages.md](contracts/inventory-reservation-messages.md), with fields `schemaVersion, messageId, messageType, attemptId, transactionId, lines` (`List<ReserveLineV1>`). Allowed `messageType` values are "`ReserveRequested` | `CommitRequested` | `CancelRequested`"; `transactionId` and `lines` are required only for `ReserveRequested` ("1–1,000 distinct SKUs, quantity ≥ 1")
- [x] T009 [P] Create `SC/inventory/v1/InventoryReplyV1.java` and `SC/inventory/v1/ReplyErrorV1.java` (`code`, `message`) per the contract, with fields `schemaVersion, messageId, messageType, attemptId, command, reservation` (`ReservationV1`, nullable) and `error` (nullable). `messageType` ∈ "`Reserved` | `Committed` | `Released` | `Cancelled` | `Conflict`"
- [x] T010 [P] Add the fixtures under `SCT/resources/fixtures/messages/`: `product-changed-v1.json`, `product-changed-inactive-v1.json`, `reserve-requested-v1.json`, `commit-requested-v1.json`, `cancel-requested-v1.json`, `reply-reserved-partial-v1.json`, `reply-released-v1.json`, `reply-cancelled-v1.json`, `reply-committed-v1.json`, `reply-conflict-v1.json`, copying the examples in the two contract files
- [x] T011 Extend `SCT/java/edu/northeastern/cs6510/contracts/FixtureCompatibilityTest.java` so every new fixture round-trips through its record without losing fields, an extra unknown field is ignored, and `ProductEventV1.validate()` rejects mutated fixtures with a negative price or blank name (depends on T007–T010)
- [x] T012 [P] Create `CAT/messaging/KafkaConfig.java`: an idempotent producer (`acks=all`, `enable.idempotence=true`, `linger.ms=0`), `KafkaAdmin`, and a `NewTopic` bean for `catalog.products.v1` (1 partition, replication 1, `cleanup.policy=compact`, `min.compaction.lag.ms=0`). Add `spring.kafka.bootstrap-servers: "${KAFKA_BOOTSTRAP:localhost:9092}"` to `domain-services/catalog-service/src/main/resources/application.yml` and `KAFKA_BOOTSTRAP: kafka:29092` plus `depends_on: kafka` to the `catalog` service in `DEP/docker-compose.yml`
- [x] T013 [P] Create `INV/messaging/KafkaConfig.java`: the producer as in T012; a `NewTopic` for `inventory.replies.v1` (12 partitions, `delete`, `retention.ms` = 7 days) and one for `inventory.dlt.v1` (1 partition, `delete`, 14 days); a `DefaultErrorHandler` with `ExponentialBackOff(200 ms ×2, max 5 s, 4 attempts)` and a `DeadLetterPublishingRecoverer` to `inventory.dlt.v1` that adds the `dlt-*` headers, with `DeserializationException`, `IllegalArgumentException`, and Jackson `JsonProcessingException` classified not-retryable; a `messaging.dlt.published{topic}` counter. Add the Kafka bootstrap setting to `domain-services/inventory-service/src/main/resources/application.yml` and `KAFKA_BOOTSTRAP: kafka:29092` to `inventory` in `DEP/docker-compose.yml`
- [x] T014 [P] Create `CO/messaging/KafkaConfig.java` with the same error handler and recoverer as T013, publishing to `checkout.dlt.v1`, plus `NewTopic` beans for `inventory.commands.v1` (12 partitions, `delete`, 7 days) and `checkout.dlt.v1` (1 partition, 14 days). Add a separate command producer (`acks=all`, `enable.idempotence=true`, `linger.ms=0`) and leave the accepted-scan producer in `CO/outbox/KafkaProducerConfig.java` unchanged. Bring accepted scans up to the shared envelope (constitution: every message carries its producer and occurrence time; [contracts/accepted-scan-event.md](contracts/accepted-scan-event.md)): in `CO/outbox/AcceptedScanEventMapper.java` add the headers `producer=checkout`, `occurredAt` (= `acceptedAt`, ISO-8601 UTC with microseconds), and `messageId` (= `eventId`), keeping the existing `eventId`, `eventType`, and `schemaVersion` headers and the body unchanged. Extend `COT/outbox/AcceptedScanSerializationTest.java` to assert all six headers
- [x] T015 [P] Create `AN/messaging/DeadLetterConfig.java` with the same error handler and recoverer, publishing to `analytics.dlt.v1`, and a `NewTopic` for `analytics.dlt.v1` (1 partition, 14 days). Wire it into the existing container factory in `AN/ingest/KafkaConsumerConfig.java` without changing `analytics-scan-ingest` offsets or ordering
- [x] T016 [P] Add a broker readiness check to each service that lacks one. Create `CAT/health/KafkaHealthIndicator.java`, `INV/health/KafkaHealthIndicator.java`, and `AN/health/KafkaHealthIndicator.java` (AdminClient `describeCluster` with a 2 s timeout, as in `CO/health/KafkaHealthIndicator.java`), and add `kafka` to `management.endpoint.health.group.readiness.include` in all four services' `application.yml`

**Checkpoint**: Contracts compile and round-trip, every service connects to Kafka, and the topics
are created by their owners on startup.

---

## Phase 3: User Story 1 - Check Out While Services Talk Only Through the Broker (Priority: P1) 🎯 MVP

**Goal**: The scan and completion paths use only Kafka between domain services, and the public
behavior is unchanged.

**Independent Test**: Quickstart Q2 and Q3. Run the default load client with zero errors, and
check that no domain service holds another domain service's client or address.

### Tests for User Story 1 ⚠️

> Write these first and confirm they fail before implementing.

- [x] T017 [P] [US1] Create `IT/boundaries/NoInterServiceHttpTest.java`, using the same source-scanning style as `IT/boundaries/ModuleBoundaryTest.java`, for the four domain modules only (the gateway is excluded). It fails if any `src/main/java` file references `java.net.http.HttpClient`, `RestClient`, `RestTemplate`, or `WebClient`; if any `application.yml` has a key ending in `.url` other than `spring.datasource.url`; or if `DEP/docker-compose.yml` gives a domain service an environment value matching `http://(catalog|checkout|inventory|analytics)[:/]` (research R10)
- [x] T018 [P] [US1] Create `domain-services/catalog-service/src/test/java/edu/northeastern/cs6510/catalog/publish/ProductPublisherIT.java` (Testcontainers MySQL + Kafka). After startup, `catalog.products.v1` holds one record per `catalog_items` row, keyed by SKU, whose bodies match `product-changed-v1.json` and whose headers include the envelope, and `catalog_publication.published_version` equals `catalog_metadata.catalog_version`. A second start publishes nothing new. Bumping `catalog_version` republishes every SKU
- [x] T019 [P] [US1] Create `COT/catalog/ProductEventListenerIT.java` (Testcontainers MySQL + Kafka). Publishing three `ProductChanged` records fills `catalog_projection` and sets `projection_state.topic_offset` to 2 in one transaction; an `active=false` record deletes the SKU; a record with a lower `catalogVersion` than the stored one is skipped; restarting the listener resumes from `topic_offset + 1`, not from group offsets; readiness stays `OUT_OF_SERVICE` until the end offset captured at assignment is reached. While the projection is still replaying, `ProductLookup` returns `Unverifiable` (public `503 SERVICE_UNAVAILABLE`) for a SKU it doesn't hold yet; once caught up, the same miss returns `UnknownSku`
- [x] T020 [P] [US1] Create `INVT/reservation/ReservationCommandListenerIT.java` (Testcontainers MySQL + Kafka). For each fixture command, exactly one `reply_outbox` row commits with the reservation change and its reply reaches `inventory.replies.v1` with `correlationId = attemptId` and `causationId` set to the command's `messageId`. A repeated `ReserveRequested` gives a second reply with the same stored state and no second stock effect. `CommitRequested` for an unknown attempt replies `Conflict` with `RESERVATION_NOT_FOUND`
- [x] T021 [P] [US1] Create `COT/completion/CompletionSagaTest.java` (unit test, replacing `AttemptDriverTest`). For each row of the [data-model.md](data-model.md) transition table, check that applying the reply in the expected state performs the transition and writes exactly the listed command (or none), and that a reply in any other state changes nothing and increments `checkout.saga.reply.ignored{reason}`. `Reserved` lines that don't match the snapshot, or a `Conflict` reply, set `needs_attention`
- [x] T022 [US1] Re-point the system tests `IT/completion/PartialFulfillmentTest.java`, `ConcurrentLastUnitTest.java`, `BusyScanTest.java`, `CompletionPreconditionTest.java`, and `IT/contract/FullGatewayContractTest.java` at the message saga. Replace any assertion that reads Inventory's HTTP reservation API with `IT/support/InventoryDb.java` queries. The asserted public responses must stay exactly as in 004. Tag `CancelCommitRaceTest`, `DuplicateCompletionJoinTest`, `InterruptionRecoveryTest`, and `LostResponseTest` with `@Tag("needs-us2")` and add `needs-us2` to `excluded.test.groups` in `domain-services/integration-tests/pom.xml` until T051 re-enables them

### Implementation for User Story 1: Catalog event stream

- [x] T023 [US1] Add `domain-services/catalog-service/src/main/resources/db/migration/V4__catalog_publication.sql`: table `catalog_publication (id TINYINT PK CHECK (id = 1), published_version BIGINT NULL, published_at TIMESTAMP(6) NULL)`, seeded with `(1, NULL, NULL)`. The invariant is "`published_version ≤ catalog_metadata.catalog_version`"
- [x] T024 [US1] Create `CAT/publish/ProductPublisher.java` (research R2). It runs at startup and every 5 s on a scheduler thread. When `published_version` is NULL or below `catalog_version`, or the topic's end offset is 0 while `published_version` is set, it reads every `catalog_items` row (active and inactive), sends one `ProductEventV1` per SKU (key = SKU, envelope headers with `producer=catalog`), flushes, waits for every acknowledgment, and only then updates `catalog_publication`. A send failure leaves `published_version` unchanged and retries on the next tick (depends on T012, T023)
- [x] T025 [US1] Delete `CAT/internal/CatalogInternalController.java` and `domain-services/catalog-service/src/test/java/edu/northeastern/cs6510/catalog/internal/CatalogInternalContractTest.java`, and remove from `SC/catalog/v1/` the records `CatalogVersionV1.java` and `ProductPageV1.java`, with their fixtures `catalog-version-v1.json` and `product-page-v1.json` and their cases in `FixtureCompatibilityTest`. `ProductV1` is deleted only if nothing references it after T029, T031, and T032
- [x] T026 [P] [US1] Add `domain-services/checkout-service/src/main/resources/db/migration/V7__messaging.sql`. It creates `command_outbox (outbox_id BIGINT AUTO_INCREMENT PK, message_id CHAR(36) NOT NULL UNIQUE, attempt_id VARCHAR(64) NOT NULL, message_type VARCHAR(32) NOT NULL CHECK IN ('ReserveRequested','CommitRequested','CancelRequested'), payload JSON NOT NULL, created_at TIMESTAMP(6) NOT NULL, published_at TIMESTAMP(6) NULL, INDEX idx_command_outbox_pending (published_at, outbox_id))`. It adds to `completion_attempts` the columns `last_command_at TIMESTAMP(6) NOT NULL` (backfill `updated_at`) and `command_sends INT NOT NULL DEFAULT 1`, and drops `step_failures`, `lease_owner`, and `lease_until`. It adds `projection_state.topic_offset BIGINT NULL` and drops `projection_state.last_verified_at`
- [x] T027 [P] [US1] Add `domain-services/inventory-service/src/main/resources/db/migration/V9__messaging.sql`. It creates `reply_outbox` with the same columns as `command_outbox` plus `causation_id CHAR(36) NOT NULL`, with `message_type` CHECK IN `('Reserved','Committed','Released','Cancelled','Conflict')`. It adds `projection_state.topic_offset BIGINT NULL` and drops `projection_state.last_verified_at`
- [x] T028 [P] [US1] Add `domain-services/analytics-service/src/main/resources/db/migration/V5__projection_offset.sql`, which adds `projection_state.topic_offset BIGINT NULL` and drops `projection_state.last_verified_at`
- [x] T029 [US1] Create `CO/catalog/ProductEventListener.java`: a batch `@KafkaListener` on `catalog.products.v1`, group `checkout-catalog-projection`, implementing `ConsumerSeekAware` (research R3). On assignment it seeks to `topic_offset + 1`, or to the beginning when the offset is NULL, and records the end offset as the catch-up target. Per batch, in one transaction, it upserts each valid record with `catalogVersion ≥` the stored one, deletes SKUs whose record has `active=false`, and sets `topic_offset` to the batch's last offset. After commit it swaps the in-memory map. Rewrite `CO/catalog/CatalogProjection.java` to drop `STALE`, `markVerified`, `upsertSingle`, and `replaceAll`, and to expose `apply(batch, lastOffset)`, `topicOffset()`, and `caughtUp()`. Delete `CO/catalog/CatalogClient.java`, `CO/catalog/CatalogProjectionLoader.java`, and `COT/catalog/CatalogClientContractTest.java` (depends on T026)
- [x] T030 [US1] Update `CO/scan/ProductLookup.java` and `CO/scan/ScanService.java`, removing the Catalog fallback. A projection hit is accepted. A miss returns the existing unknown-item error only when `CatalogProjection.caughtUp()` is true; while the projection is still replaying, a miss returns the existing `Unverifiable` result, which `ScanService` already maps to `503 SERVICE_UNAVAILABLE` (004 amendment A6). Readiness alone does not stop traffic: the gateway forwards regardless and Spring keeps serving while `OUT_OF_SERVICE`, so this check is what prevents valid SKUs being rejected during a replay. Update `CO/health/CatalogProjectionHealthIndicator.java` to report `OUT_OF_SERVICE (REPLAYING)` until `caughtUp()` and the projection is non-empty, then `UP (CURRENT)`, with lag exposed as the gauge `catalog.projection.lag`. Add `catalogProjection` to Checkout's readiness group (depends on T029)
- [x] T031 [P] [US1] Create `INV/catalog/ProductEventListener.java` (group `inventory-catalog-projection`), using T029's design but projecting only `name` into `catalog_names`. Rewrite `INV/catalog/CatalogNameProjection.java` accordingly, and make the low-stock view return `503 SERVICE_UNAVAILABLE` (as 004 does for "no projected name") while the projection is still replaying, rather than omitting or blanking names. Update `INV/health/CatalogProjectionHealthIndicator.java` as in T030, and delete `INV/catalog/CatalogClient.java` and `INV/catalog/CatalogNameProjectionLoader.java` (depends on T027)
- [x] T032 [P] [US1] Create `AN/catalog/ProductEventListener.java` (group `analytics-catalog-projection`), using T031's design, and rewrite `AN/catalog/CatalogNameProjection.java`. `GET /analytics/popular-items` returns `503 SERVICE_UNAVAILABLE` while the projection is still replaying, as T031 does for low stock. Update the projection part of `AN/health/AnalyticsHealthIndicators.java` and delete `AN/catalog/CatalogClient.java` and `AN/catalog/CatalogNameProjectionLoader.java` (depends on T028)

### Implementation for User Story 1: Reservation commands and replies

- [x] T033 [US1] Create `INV/messaging/ReplyOutbox.java`, whose `write(Envelope, InventoryReplyV1)` inserts one `reply_outbox` row and must be called inside the caller's transaction. Create `INV/messaging/OutboxSender.java`, which registers `TransactionSynchronization.afterCommit` to hand new rows to a single sender thread; that thread sends to `inventory.replies.v1` (key = `attemptId`) and marks acknowledged rows `published_at` in one batched `UPDATE … WHERE outbox_id IN (…)`. To keep one attempt's messages in send order (FR-010): before sending a newly committed row, the thread selects any older unsent rows with the same `attempt_id` and sends them first, in `outbox_id` order. The unsent-row sweeper added in T050 runs on the same thread, so the two paths never interleave (depends on T013, T027)
- [x] T034 [US1] Change `INV/reservation/ReservationService.java` so `reserve`, `commit`, and `cancel` take a `ReplySink` (new interface `INV/reservation/ReplySink.java`, with `void reply(ReservationV1 stored, String conflictCode)`), invoked inside the same `TransactionTemplate` callback that changes the reservation, including idempotent repeats and conflicts. Map every `InternalApiException` conflict and the 400 validation error to a `conflictCode` (`INVALID_RESERVATION_REQUEST`, `RESERVATION_MISMATCH`, `RESERVATION_NOT_FOUND`, `RESERVATION_NOT_COMMITTABLE`, `RESERVATION_COMMITTED`) instead of throwing. Keep `withLockRetries`, the lock order, and `SaleApplier` unchanged (depends on T033)
- [x] T035 [US1] Create `INV/reservation/ReservationCommandListener.java`: a `@KafkaListener` on `inventory.commands.v1`, group `inventory-reservations`, concurrency 6, `AckMode.RECORD`, using T013's error handler. It parses `Envelope` and `InventoryCommandV1`, dispatches on `messageType` to `ReservationService` with a `ReplySink` that writes `InventoryReplyV1` (`messageType` from the stored state, or `Conflict`; `causationId` = command `messageId`; `correlationId` = `attemptId`) through `ReplyOutbox`. Unparseable records and unknown types throw a not-retryable exception and go to `inventory.dlt.v1` (depends on T034)
- [x] T036 [US1] Delete `INV/reservation/ReservationController.java` and convert `INVT/reservation/ReservationContractTest.java` into fixture assertions on `InventoryReplyV1`, or delete it if T020 covers the same cases. Keep `INV/lowstock/LowStockController.java`, `INV/ops/InventoryOpsController.java`, and the test-provisioning endpoint (depends on T035)
- [x] T037 [US1] Create `CO/messaging/CommandOutbox.java`, whose `write(attemptId, messageType, payload)` inserts one `command_outbox` row with a new UUIDv7 `message_id` (`CO/completion/UuidV7.java`) inside the caller's transaction. Create `CO/messaging/OutboxSender.java`, as in T033 (including the same per-attempt ordering rule and single sender thread), sending to `inventory.commands.v1` with key = `attemptId` and the envelope headers `producer=checkout`, `correlationId=attemptId` (depends on T014, T026)
- [x] T038 [US1] Create `CO/completion/CompletionSaga.java` (research R5, data-model state machine). It has `start(attempt)`, which writes `ReserveRequested` inside the freeze transaction, and `onReply(Envelope, InventoryReplyV1)`. Each transition is one transaction made of a conditional `UPDATE completion_attempts … WHERE attempt_id = ? AND state = ?`, its side writes (reuse `storeReserved`, `finalizeAttempt`, and `clearActiveAttempt` from `CO/completion/JdbcCompletionAttemptRepository.java`), and the next command via `CommandOutbox`. Every transition sets `command_sends = 1`, `last_command_at = now`, and `next_retry_at = now + 1 s`. Zero rows updated means the reply is ignored and counted. `Conflict` replies and mismatched lines park the attempt with `needs_attention`. Remove the lease methods from `CO/completion/CompletionAttemptRepository.java` and its JDBC implementation (depends on T037)
- [x] T039 [US1] Create `CO/completion/AttemptWaiters.java`, a map from `attemptId` to a list of `CompletableFuture<Outcome>`. `await(attemptId, deadline)` returns the terminal outcome, or `UNRESOLVED` at the deadline, re-reading the attempt from the database every 250 ms as a fallback. `CompletionSaga` completes the futures in `afterCommit` of each terminal transition (depends on T038)
- [x] T040 [US1] Create `CO/messaging/ReplyListener.java`: a `@KafkaListener` on `inventory.replies.v1`, group `checkout-saga`, concurrency 6, `AckMode.RECORD`, using T014's error handler, that calls `CompletionSaga.onReply` (depends on T038)
- [x] T041 [US1] Rewrite `CO/completion/CompletionService.java`. The freeze transaction creates the attempt and calls `CompletionSaga.start`. New and joined requests both wait on `AttemptWaiters.await(attemptId, deadline)`, and outcomes map to responses exactly as today: `FINALIZED` → receipt, `REJECTED` → `409 INSUFFICIENT_STOCK`, `CANCELLED`/`UNRESOLVED` → `503 SERVICE_UNAVAILABLE`. Delete `CO/completion/AttemptDriver.java`, `CO/inventory/` (the whole package), `CO/health/InventoryHealthIndicator.java`, `COT/completion/AttemptDriverTest.java`, `COT/completion/FakeInventory.java`, `COT/inventory/HttpInventoryClientTest.java`, and `COT/inventory/InventoryClientContractTest.java`. Update `COT/completion/InMemoryAttemptRepository.java` for the new repository interface (depends on T039, T040)
- [x] T042 [US1] Remove `catalog.url` and `inventory.url` from `domain-services/checkout-service/src/main/resources/application.yml`, `catalog.url` from the Inventory and Analytics `application.yml`, and `CATALOG_URL`/`INVENTORY_URL` from the `checkout`, `inventory`, and `analytics` services in `DEP/docker-compose.yml`. Add `depends_on: kafka` (condition `service_healthy`) to each domain service (depends on T029, T031, T032, T041)
- [ ] T043 [US1] Run `mvn -f domain-services/pom.xml verify` with JDK 21 and the `system` profile. T017–T022 must pass, then run quickstart Q2 and Q3 by hand and record the outcome in `DEP/reports/event-driven/README.md` under "Validation log" (depends on T042)

**Checkpoint**: The MVP works. Shoppers check out through the gateway, and domain services talk
only through Kafka.

---

## Phase 4: User Story 2 - Purchases Stay Correct When Messages Are Lost, Late, or Repeated (Priority: P1)

**Goal**: Resends, cancellation, dead-lettering, and crash recovery make every attempt settle
exactly once.

**Independent Test**: Quickstart Q4, Q6, Q7, and Q8 (poison message part), each followed by
`reconcile.sh` passing.

### Tests for User Story 2 ⚠️

- [x] T044 [P] [US2] Rewrite `COT/recovery/RecoveryWorkerTest.java` for the resend sweep. An attempt with `next_retry_at ≤ now` is claimed once, even by two concurrent sweeps (conditional `UPDATE … AND next_retry_at = ?`), and gets exactly one new `command_outbox` row for its current state. `command_sends` increments with backoff "250 ms doubling to a 5 s cap". `RESERVING` older than `cancel-after` moves to `CANCELLING` with `CancelRequested`. "`command_sends > attention-after` (20)" sets `needs_attention` and stops resending. `COMMITTING` never becomes `CANCELLING`
- [ ] T045 [P] [US2] Create `IT/messaging/DuplicateDeliveryTest.java` (`@Tag("system")`), which runs quickstart Q4 with `MESSAGING_FAULTS_DUPLICATE_SENDS=true`. Reconciliation passes, receipt lines are not duplicated, the Analytics count equals the accepted scans, and `checkout.saga.reply.ignored` is greater than 0
- [ ] T046 [P] [US2] Create `IT/messaging/BrokerRestartTest.java` (`@Tag("system")`), which runs quickstart Q7. While Kafka restarts, scans succeed and completions return 503. Within 60 s of Kafka being healthy, every outbox's pending count and every consumer group's lag are 0, no attempt is unresolved, and reconciliation passes
- [ ] T047 [P] [US2] Create `IT/messaging/PoisonMessageTest.java` (`@Tag("system")`), which runs the poison part of quickstart Q8. A malformed record on `inventory.commands.v1` and one on `inventory.replies.v1` each appear once in `inventory.dlt.v1` / `checkout.dlt.v1` with `dlt-*` headers within 10 s, and a valid command sent afterwards still completes
- [ ] T048 [P] [US2] Create `IT/completion/LateReplyTest.java` (`@Tag("system")`). With Inventory stopped, a completion returns `503` within 5 s. Inventory is started again within 20 s. The attempt then reaches `FINALIZED` within 60 s, and a repeated `complete` on the now-empty basket returns `409 TRANSACTION_NOT_OPEN`, with one receipt and one stock decrement

### Implementation for User Story 2

- [x] T049 [US2] Rewrite `CO/recovery/RecoveryWorker.java` and `CO/recovery/RecoveryProperties.java` as the resend sweep ([data-model.md](data-model.md) rules 3–4). Every `scan-interval` (1 s), it claims due attempts with a conditional `UPDATE`, writes the current state's command again through `CommandOutbox` in the same transaction, and applies `cancel-after` (30 s, `RESERVING` only) and `attention-after` (20). It makes no remote calls. Remove `lease-duration`, `in-request-retries`, and `in-request-backoff` from `application.yml`, and keep the existing environment overrides for the remaining keys. Keep `CO/ops/OperationsController.java` retry working: it clears `needs_attention` and sets `next_retry_at = now` (depends on T038)
- [x] T050 [US2] Add an unsent-row sweeper to `CO/messaging/OutboxSender.java` and `INV/messaging/OutboxSender.java`, running on each sender's existing single thread (T033) so its sends never interleave with after-commit sends for the same attempt. Every 1 s it selects "`published_at IS NULL AND created_at < now − 2 s`" ordered by `outbox_id`, limit 500, sends, and marks. It also purges published rows older than 24 h. Expose the gauges `checkout.command_outbox.pending` and `inventory.reply_outbox.pending` and add them to the health detail (depends on T033, T037)
- [ ] T051 [US2] Re-point `IT/completion/CancelCommitRaceTest.java`, `DuplicateCompletionJoinTest.java`, `InterruptionRecoveryTest.java`, and `LostResponseTest.java` from Toxiproxy to the faults in research R13 (stopping Inventory, `HaltingFaultInjector` checkpoints, and the duplicate-send switch). Add an Inventory crash case to `InterruptionRecoveryTest` (FR-028): start Inventory with `MESSAGING_FAULTS_HOLD_SENDS=true`, complete a purchase so its reply rows commit unsent, then restart Inventory with the flag off. Assert each held reply is sent once by the sweeper, the attempt settles once, and reconciliation passes. Rewrite `IT/support/Faults.java` to drive `docker compose stop/start` and the checkout fault endpoint instead of the Toxiproxy API, then remove their `@Tag("needs-us2")` and the `needs-us2` entry from `excluded.test.groups` in `domain-services/integration-tests/pom.xml` (depends on T050, T052)
- [x] T052 [US2] Rename the checkpoints in `CO/fault/FaultInjector.java` to `AFTER_FREEZE`, `AFTER_RESERVED_APPLIED`, and `AFTER_COMMITTED_APPLIED`, called by `CompletionSaga` after the matching transaction commits. Add the property `messaging.faults.duplicate-sends` (default `false`, env `MESSAGING_FAULTS_DUPLICATE_SENDS`); when it is true, both `OutboxSender`s and `CO/outbox/OutboxRelay.java` send every record twice. Add `messaging.faults.hold-sends` (default `false`, env `MESSAGING_FAULTS_HOLD_SENDS`) to `INV/messaging/OutboxSender.java`; when it is true the sender and its sweeper send nothing, so committed replies stay unsent. This simulates a crash between the reservation commit and the reply send. Remove the `toxiproxy` service and its wiring from `DEP/docker-compose.faults.yml` and delete `DEP/toxiproxy/` (depends on T049)
- [ ] T053 [US2] Run T044–T048 and T051 with the `system` profile, followed by `DEP/scripts/reconcile.sh`, and record the results in the "Validation log" of `DEP/reports/event-driven/README.md` (depends on T051)

**Checkpoint**: US1 and US2 both work. Purchases survive duplicates, restarts, late replies, and
poison messages.

---

## Phase 5: User Story 3 - One Service Being Down Does Not Stop the Others (Priority: P2)

**Goal**: Stopping Catalog, Analytics, or Inventory degrades only what depends on it, and each
service catches up on restart.

**Independent Test**: Quickstart Q1 and Q5.

### Tests for User Story 3 ⚠️

- [ ] T054 [P] [US3] Extend `IT/deployment/IndependentStartupTest.java` with quickstart Q1. After the projection rebuild endpoint is called and the service restarted, readiness is `OUT_OF_SERVICE (REPLAYING)`, then `UP`, with 2,000 projected SKUs, for Checkout, Inventory, and Analytics
- [ ] T055 [P] [US3] Create `IT/deployment/ServiceOutageTest.java` (`@Tag("system")`), with one case per row of the quickstart Q5 table: Catalog down for 60 s, Analytics down for 60 s, Inventory down for 20 s (late reply), and Inventory down for 60 s (past `cancel-after`: attempts that were waiting end `CANCELLED`, physical stock and the open basket are unchanged, and a completion after Inventory returns succeeds). Each case asserts the "while stopped" and "after restart" columns, then that reconciliation passes

### Implementation for User Story 3

- [x] T056 [P] [US3] Add `POST /internal/v1/ops/catalog-projection/rebuild` to `CO/ops/OperationsController.java`, `INV/ops/InventoryOpsController.java`, and a new `AN/ops/ProjectionOpsController.java`. In one transaction it deletes the projection rows and sets `topic_offset = NULL`, then makes its listener re-seek to the beginning (via `ConsumerSeekAware` callbacks)
- [x] T057 [P] [US3] Create `CAT/ops/CatalogOpsController.java` with `POST /internal/v1/ops/republish`, which sets `catalog_publication.published_version = NULL` so `ProductPublisher` republishes on its next tick
- [x] T058 [US3] Configure graceful shutdown in all four services (research R9). Stop the listener containers first through a `SmartLifecycle` phase above the outbox senders, then drain each `OutboxSender` queue with a 10 s cap; the scan relay keeps its 15 s join. Keep `spring.lifecycle.timeout-per-shutdown-phase: 20s` (depends on T050)
- [ ] T059 [US3] Run T054 and T055 and record the results in the "Validation log" of `DEP/reports/event-driven/README.md` (depends on T056–T058)

**Checkpoint**: Each service can be stopped on its own and catches up afterwards.

---

## Phase 6: User Story 4 - Submit Load-Test Reports and an Architecture Diagram (Priority: P2)

**Goal**: The graded artifacts are committed and linked: the default report, the stress report
(100 × 120), and the architecture diagram.

**Independent Test**: Quickstart Q9–Q11. From the root `README.md`, reach both JSON reports and
the diagram in under 2 minutes.

### Implementation for User Story 4

- [x] T060 [P] [US4] Extend `DEP/scripts/capture-observations.sh` to sample, per run, the consumer lag of the groups `checkout-saga`, `inventory-reservations`, `analytics-scan-ingest`, and the three `*-catalog-projection` groups (via `kafka-consumer-groups.sh` in the broker container), the outbox pending gauges, `messaging.dlt.published`, and the p50/p95/p99 of the `checkout.saga.roundtrip` timer
- [x] T061 [P] [US4] Add the `checkout.saga.roundtrip` timer to `CO/completion/CompletionSaga.java`. It measures from the `ReserveRequested` row's `created_at` to the commit of the terminal transition, tagged by `outcome`
- [x] T062 [P] [US4] Extend `DEP/scripts/reconcile.sh` with the data-model "Reconciliation additions". The script fails if any of `command_outbox`, `reply_outbox`, or `accepted_scan_outbox` has unsent rows; if any of the three dead-letter topics has records; if `catalog_publication.published_version` differs from `catalog_metadata.catalog_version`; or if any consumer's `projection_state.catalog_version` differs from that version
- [ ] T063 [US4] Run `DEP/scripts/run-measurement.sh --topology event-driven --compose-file DEP/docker-compose.yml --stock-sql DEP/provisioning/measured-stock.sql --profile default --report-dir DEP/reports/event-driven/default` (3 runs), then the same with `--profile stress100 --report-dir DEP/reports/event-driven/stress` (3 runs), on the same host as T004. Commit every `report-*.json`, `observations-*.json`, and `reconciliation-*.txt` (depends on T053, T059, T060–T062)
- [ ] T064 [US4] Add `DEP/scripts/compare-event-driven.sh` (004's `compare-reports.sh` is built around its SC-009 verdicts, so it is left as is) to compare `baseline-004/{default,stress}` with `{default,stress}`, and write `DEP/reports/event-driven/COMPARISON.md` with per-run rows and medians for scan, start, and complete mean/p95/p99, throughput, and errors (depends on T063)
- [ ] T065 [US4] Write `DEP/reports/event-driven/NOTES.md`. It names the graded default and stress report (the median-scan-p95 run of each profile) by relative link; records topology, partitions, listener concurrency, producer settings, DB pool sizes, stock seed, hardware, and commit hashes; categorizes and explains every error; and states the scan and complete p95 deltas against `baseline-004` without attributing them to architecture alone (FR-025, SC-011) (depends on T064)
- [x] T066 [P] [US4] Rewrite `DEP/ARCHITECTURE.md` around a Mermaid `flowchart LR` (research R12) with: the load client; the gateway and its four HTTP edges; Catalog, Checkout, Inventory, and Analytics, each with its own MySQL database; a Kafka subgraph containing `catalog.products.v1`, `checkout.accepted-scans.v1`, `inventory.commands.v1`, `inventory.replies.v1`, `checkout.dlt.v1`, `inventory.dlt.v1`, and `analytics.dlt.v1`; and one labeled arrow per publish and consume, with message types. Below it, embed the topic catalog table from [contracts/README.md](contracts/README.md) and the happy-path sequence diagram from [data-model.md](data-model.md)
- [x] T067 [US4] Commit `DEP/architecture.svg`, drawn by hand from the Mermaid source so it needs no toolchain, and create `DEP/scripts/render-diagram.sh`, which renders the first Mermaid block of `DEP/ARCHITECTURE.md` to `DEP/architecture-mermaid.svg` with a pinned `@mermaid-js/mermaid-cli` (npx, or Docker as a fallback) for comparison (depends on T066)
- [x] T068 [US4] Add an "Event-driven submission (005)" section to the repository root `README.md` with links to the graded default report, the graded stress report (from T065), `domain-services/deployment/architecture.svg`, `domain-services/deployment/ARCHITECTURE.md`, `DEP/reports/event-driven/NOTES.md`, and the [contracts README](contracts/README.md). Update `DEP/README.md` so the entry point lists the same links (depends on T065, T067)

**Checkpoint**: The graded artifacts are committed and linked from the root README.

---

## Phase 7: User Story 5 - Operator Views Stay Correct (Priority: P3)

**Goal**: Low-stock and popular-item views show correct names from the event-built projections.

**Independent Test**: Quickstart Q8 (replay and ranking parts).

- [ ] T069 [P] [US5] Update `IT/inventory/LowStockViewTest.java` and `IT/analytics/PopularItemsViewTest.java` so their expected names come from `catalog_items` as published, and add a case that rebuilds the projection (T056) between two requests and gets identical responses
- [ ] T070 [P] [US5] Extend `IT/analytics/KnownSequenceRankingTest.java` and `IT/analytics/ReplayDuplicateTest.java` to assert that the publication at scan 1,500 matches scans 501–1,500, before and after an Analytics projection rebuild and with `MESSAGING_FAULTS_DUPLICATE_SENDS=true`
- [ ] T071 [US5] Run T069 and T070 and record the results in the "Validation log" of `DEP/reports/event-driven/README.md` (depends on T069, T070)

**Checkpoint**: All five stories work on their own.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T072 [P] Add a "Superseded by 005" note at the top of `specs/004-domain-services/contracts/catalog-internal-api.md` and `specs/004-domain-services/contracts/inventory-internal-api.md` (reservation sections only), linking [contracts/README.md](contracts/README.md)
- [ ] T073 [P] Update the 004 contracts index `specs/004-domain-services/contracts/README.md` so its rule "every synchronous call has explicit connect and read timeouts" points to 005 for communication between services
- [ ] T074 Run every quickstart scenario Q0–Q11 in order on a clean stack (`DEP/scripts/reset.sh`), and record pass/fail with timestamps in the "Validation log" of `DEP/reports/event-driven/README.md`
- [x] T075 Remove the Sync Impact Report HTML comment from the top of `.specify/memory/constitution.md` before the constitution amendment is committed

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 → T004. T002 and T003 are independent. T004 can run in the
  background while Phase 2 starts, as long as the measurement host isn't also used for
  development builds during the run.
- **Foundational (Phase 2)**: Depends on T002. Blocks every story.
- **US1 (Phase 3)**: Depends on Phase 2. It is the MVP and blocks US2 (which shares the saga
  and outbox code) and US4's measurement runs.
- **US2 (Phase 4)**: Depends on US1 (T038, T033, T037).
- **US3 (Phase 5)**: Depends on US1. T058 also needs T050 from US2.
- **US4 (Phase 6)**: T060–T062 and T066 can start after US1. T063 needs US1–US3 complete, so
  the graded runs measure the final build.
- **US5 (Phase 7)**: Depends on US1 and T056 (US3).
- **Polish (Phase 8)**: After all stories.

### Within User Story 1

- Tests T017–T021 first, and they must fail.
- Catalog stream: T023 → T024 → T025 after T029, T031, and T032. The migrations T026–T028
  run in parallel. T029 → T030. T031 and T032 run in parallel with T029.
- Reservation path: T033 → T034 → T035 → T036, and in parallel T037 → T038 → T039/T040 → T041.
- T042 needs both paths. T043 is last.

### Parallel Opportunities

- Phase 2: T007–T010 together, and T012–T016 together (each in a different module).
- US1: T017–T021 together; T026, T027, and T028 together; T031 and T032 together. The Inventory
  path (T033–T036) and the Checkout path (T037–T041) can be worked by two people at once.
- US2: T044–T048 together.
- US4: T060, T061, T062, and T066 together.
- US5: T069 and T070 together.

---

## Parallel Example: User Story 1

```text
# Tests first (all different files):
Task: "T017 NoInterServiceHttpTest in IT/boundaries/NoInterServiceHttpTest.java"
Task: "T018 ProductPublisherIT in catalog-service/src/test/.../publish/ProductPublisherIT.java"
Task: "T019 ProductEventListenerIT in COT/catalog/ProductEventListenerIT.java"
Task: "T020 ReservationCommandListenerIT in INVT/reservation/ReservationCommandListenerIT.java"
Task: "T021 CompletionSagaTest in COT/completion/CompletionSagaTest.java"

# Migrations, one per service:
Task: "T026 checkout V7__messaging.sql"
Task: "T027 inventory V9__messaging.sql"
Task: "T028 analytics V5__projection_offset.sql"

# Two implementation tracks:
Track A (Inventory): T033 → T034 → T035 → T036
Track B (Checkout):  T037 → T038 → T039 + T040 → T041
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 (start T004's 004 measurement early, since it takes about 25 minutes)
2. Phase 2
3. Phase 3 (US1)
4. **Stop and validate**: run Q2 and Q3, plus one default load-client run. At this point the
   assignment's core requirement, communication between services through events only, is
   already met.

### Incremental Delivery

1. US1 → events only, public behavior unchanged (MVP)
2. US2 → correctness under message failures
3. US3 → outage tolerance and catch-up
4. US4 → graded reports and diagram (run last, on the final build)
5. US5 → operator view regression checks

### Notes

- [P] tasks touch different files and have no dependency on unfinished tasks.
- Commit after each task or logical group. The graded reports (T063) must come from a
  committed build, and `NOTES.md` records its hash.
- Never edit the load client or the mock server.
