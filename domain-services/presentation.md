---
title: Self-Checkout Domain Services Codewalk
---

# Self-Checkout Domain Services: A Codewalk

- **What we walk through**
  - One checkout, followed through the code from the gateway to the analytics view
  - `domain-services/` on branch `microservice-new`
- **Route of the tour**
  - Gateway → Catalog → scan → completion → recovery → events → analytics → tests

---

# Repository Map

- **Build layout**
  - Maven multi-module build: `domain-services/pom.xml`
  - One module per deployable: `api-gateway`, `catalog-service`, `checkout-service`, `inventory-service`, `analytics-service`
- **Shared code**
  - `service-contracts` is the only module the services depend on in common
- **Supporting modules**
  - `integration-tests`: system tests against the running stack
  - `deployment/`: Compose files, scripts, provisioning, reports
- **Package convention inside a service**
  - `api`, `internal` or `ops` controllers, plus one package per concern (`scan`, `completion`, `recovery`, `outbox`, `catalog`, `health`)

---

# Running the Stack

- **Topology: `deployment/docker-compose.yml`**
  - Four private MySQL 8.4 instances, a single Kafka node, four services, the gateway
  - Public port 8080 only; operator ports bound to `127.0.0.1`
- **Scripts: `deployment/scripts/`**
  - `start-services.sh`, `reset.sh`, `check-readiness.sh`
  - `validate-all.sh` for the full validation run
- **Configuration**
  - `services.env.example`: one password per database, never committed
  - Each service's `application.yml`: pools, timeouts, recovery settings

---

# Shared Contracts: `service-contracts`

- **Versioned records**
  - `catalog/v1`: `CatalogVersionV1`, `ProductPageV1`, `ProductV1`
  - `inventory/v1`: `ReserveRequestV1`, `ReservationV1`, `ReservationStateV1`, `InternalErrorV1`
  - `events/v1`: `AcceptedScanV1`
- **Why a `v1` package**
  - Versioning rules let a provider evolve without coordinated releases
- **Fixtures**
  - Canonical JSON under `src/test/resources/fixtures/`
  - `FixtureCompatibilityTest`, plus provider and consumer contract tests in each module

---

# Entry Point: `api-gateway`

- **Routes**
  - `CheckoutRouteHandler`: the four transaction routes, forwarded to Checkout
  - `ReadModelRouteHandler`: catalog, low-stock and popular-items reads, forwarded unchanged
- **Validation**
  - `GatewayValidation`: only the request checks the gateway has always made
- **Forwarding**
  - `UpstreamProxy`: status, content type and body passed through, including every 4xx
  - An unreachable upstream becomes `503 SERVICE_UNAVAILABLE`
- **What it deliberately lacks**
  - No datasource, no Kafka, no `/internal/**` routing

---

# Catalog Service

- **Internal API: `CatalogInternalController`**
  - `/internal/v1/catalog-version` and paged `/internal/v1/products`
  - Read-only and safe to retry; not routed by the gateway
- **Storage**
  - `CatalogRepository`, with migrations `V1__catalog.sql` and `V2__seed_catalog.sql`
- **Consumers keep projections**
  - Checkout: priced `CatalogProjection` and `CatalogProjectionLoader`
  - Inventory and Analytics: `CatalogNameProjection` (names only)
  - Version probe on a fixed delay, full reload on change, `EMPTY` / `CURRENT` / `STALE` states

---

# Scan Path in Checkout

- **Request flow**
  - `CheckoutController` → `ScanService.scan`
- **Product resolution: `ProductLookup`**
  - Projection hit is accepted; a miss is `404` only while the projection is `CURRENT`
  - Otherwise ask Catalog once; unreachable Catalog is `503`, never "not found"
  - Resolved before any transaction opens, so no remote call holds a lock
- **One local transaction: `scanOnce`**
  - `FOR SHARE` on the transaction row serializes with the completion freeze
  - Busy flag set → `409 TRANSACTION_BUSY`
  - Basket upsert, outbox row and running totals commit together
- **Retries**
  - Lock-victim retries with backoff, counted in `checkout.db.retries`

---

# Completion: Checkout Side

- **Freeze: `CompletionService.complete`**
  - Locks the transaction row and applies error precedence
  - Joins an active attempt instead of starting a second purchase
  - Creates a UUIDv7 attempt leased to the request (`UuidV7`)
- **State machine: `AttemptState`**
  - `RESERVING`, `COMMITTING`, `CANCELLING`, then `FINALIZED`, `REJECTED` or `CANCELLED`
- **Driving the saga: `AttemptDriver`**
  - `reserveStep`: grants recorded as the durable receipt intent, or `REJECTED` when nothing is granted
  - `commitStep`, then finalize; only forward once the intent is durable
  - Conditional updates on the expected state; no remote call inside a local transaction
  - Leases, and `awaitOtherDriver` for joined requests
- **Talking to Inventory: `HttpInventoryClient`**
  - 300 ms connect and 1.5 s read timeout; an unknown outcome is not a cancellation
- **Responses**
  - `ReceiptAssembler`: per-attempt receipt in exact cents
  - `409 INSUFFICIENT_STOCK`, or `503` when the deadline passes and recovery continues

---

# Completion: Inventory Side

- **Commands: `ReservationController` → `ReservationService`**
  - `reserve`, `commit`, `cancel`, `get`, each keyed by `attemptId`
- **Idempotency**
  - A repeat returns the stored outcome; `RESERVATION_MISMATCH` on different lines
  - `cancel` of an unknown id writes a tombstone, so a late reserve holds nothing
- **Locking design**
  - Reserve: one conditional single-row update per SKU, descending SKU order, no `FOR UPDATE` read
  - Commit: locks no position row, only queues the sale
  - `withLockRetries` for lock-wait and deadlock victims
- **Applying sales: `SaleApplier`**
  - Drains `sale_queue` about every 100 ms, one update per SKU per batch
  - Writes `SALE` movements; `SKIP LOCKED` for multiple instances
- **Schema**
  - `V6__reservations.sql`, `V8__sale_queue.sql`

---

# Recovery and Fault Injection

- **`RecoveryWorker`**
  - Scheduled scan of due attempts; leases ensure one driver at a time
  - Reuses `AttemptDriver`, so recovery and requests run the same steps
  - `RESERVING` past `cancel-after` → `CANCELLING`; `COMMITTING` only moves forward
  - `needs_attention` flag after repeated failures, still retrying
- **Configuration**
  - `RecoveryProperties`: lease, backoff, cancel and attention thresholds
  - `RecoveryLifecycle`: stops picking up attempts on shutdown
- **Fault injection**
  - `FaultInjector` checkpoints: `AFTER_FREEZE`, `AFTER_RESERVE_RESPONSE`, `AFTER_PREPARE`, `AFTER_COMMIT_RESPONSE`
  - `NoopFaultInjector` in normal runs; `HaltingFaultInjector` and `FaultController` under the test profile
  - `docker-compose.faults.yml`: Toxiproxy between Checkout and Inventory

---

# Accepted Scans: Outbox to Kafka

- **Outbox table**
  - `V6__accepted_scan_outbox.sql`; the row commits with the basket change in `ScanService`
- **`OutboxRelay`**
  - MySQL named lock on a dedicated connection: one relay per Checkout database
  - Selects only unpublished rows, in order
  - Marks a row published only after the broker acknowledges
  - Kafka down: rows wait and scans keep succeeding; old published rows are purged
- **Producer side**
  - `AcceptedScanEventMapper`: row to `AcceptedScanV1`
  - `KafkaProducerConfig`: idempotent producer, `acks=all`
- **Visibility**
  - `OutboxHealthIndicator` and the pending-row gauge

---

# Analytics Pipeline

- **Consumer: `AcceptedScanListener`**
  - Batch consumption, manual acknowledgment after the local transaction commits
  - Unreadable or newer-version events go to `ingest_rejections`
  - `KafkaConsumerConfig`: seek back and redeliver on failure
- **Pipes and filters: `ScanPipeline.process`**
  - `IngestFilter`: record each `eventId` once, sequence number under a lock on `ingest_state`
  - `WindowFilter`: the latest 1,000 scans, published every 500
  - `RankAndPublishFilter`: write snapshot rows, then move the pointer
- **Read side**
  - `PopularItemsController`: pointer and snapshot in one read-only transaction
  - `IngestStatusController` and `ConsumerLag`: ingest progress and lag
- **Schema**
  - `V4__ingest_and_snapshots.sql`

---

# Read Models in Inventory

- **Low-stock view**
  - `LowStockController` and `LowStockRepository`
  - Physical stock below the threshold; reservations never counted
- **Names**
  - `CatalogNameProjection`; `503` when an alerted SKU has no known name
- **Operator endpoints**
  - `InventoryOpsController` for reconciliation and diagnostics

---

# Data Ownership in Code

- **Migrations per service**
  - Flyway scripts under each module's `db/migration`
  - Each service's history shows the migration story, from shared objects to private stores
- **Removing shared objects**
  - `V*__private_store.sql`: dropping `active_items`, `low_stock`, `published_popularity` and `fulfill_checkout`
- **Health and readiness**
  - Readiness depends only on the service's own database
  - Dependency and projection health reported as details (`InventoryHealthIndicator`, `KafkaHealthIndicator`, `CatalogProjectionHealthIndicator`)
- **Error model**
  - Each service's own `ApiError`, `ApiException` and `ApiExceptionHandler`

---

# Tests

- **Module tests**
  - Unit: `AttemptDriverTest` with `FakeInventory` and `InMemoryAttemptRepository`, `RecoveryWorkerTest`, `WindowFilterTest`
  - Testcontainers: `ReservationServiceIT`, `FinalizeIT`, `OutboxRelayIT`, `ScanIngestIT`
- **Contract tests**
  - Provider and consumer tests against the shared fixtures (`ReservationContractTest`, `InventoryClientContractTest`)
- **System tests: `integration-tests`**
  - `completion/`: `PartialFulfillmentTest`, `ConcurrentLastUnitTest`, `LostResponseTest`, `CancelCommitRaceTest`
  - `analytics/`: `ReplayDuplicateTest`, `OutageRecoveryTest`
  - `deployment/IndependentStartupTest`, `contract/FullGatewayContractTest`
  - `support/`: `Stack`, `PublicApi`, `Faults`, `Reconciliation` helpers
- **Architecture tests: `boundaries/`**
  - `ModuleBoundaryTest`: modules share only `service-contracts`, no SQL names another schema
  - `PrivateStoreAccessTest`: every account tried against every instance

---

# Operations and Evidence

- **Operator endpoints**
  - `/internal/v1/ops/**` on loopback-only ports, never routed by the gateway
  - Checkout's `OperationsController`, Analytics' ingest status
- **Reconciliation**
  - `scripts/reconcile.sh`: checks R-1 to R-6 after a run
- **Measurement**
  - `run-measurement.sh`, `capture-observations.sh`, `compare-reports.sh`
  - Outputs and notes in `deployment/reports/`

---

# Q&A

- **Open floor**
  - Questions on any class or flow shown
- **Where to look afterwards**
  - `deployment/README.md` and `ARCHITECTURE.md`
