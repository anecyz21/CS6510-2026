---
title: Self-Checkout Domain Services Codewalk
---

# Self-Checkout Domain Services: A Codewalk

- Following one checkout through the code, from the gateway to the analytics view
- `domain-services/` on branch `microservice-new`

---

# Repository Map

- Maven multi-module build: `domain-services/pom.xml`
- One module per deployable: `api-gateway`, `catalog-service`, `checkout-service`, `inventory-service`, `analytics-service`
- `service-contracts`: the only code the services share
- `integration-tests` and `deployment/`

---

# Running the Stack

- `deployment/docker-compose.yml`: four private MySQL instances, Kafka, four services and the gateway
- `scripts/start-services.sh`, `reset.sh`, `check-readiness.sh`
- `services.env.example`: per-service credentials

---

# Shared Contracts: `service-contracts`

- Versioned records: `catalog/v1`, `inventory/v1`, `events/v1`
- `AcceptedScanV1`, `ReserveRequestV1`, `ReservationV1`
- JSON fixtures shared by provider and consumer tests

---

# Entry Point: `api-gateway`

- `CheckoutRouteHandler` and `ReadModelRouteHandler`: the public routes
- `GatewayValidation`: field checks before forwarding
- `UpstreamProxy`: forwarding and passing errors through
- No datasource, so no domain logic

---

# Catalog Service

- `CatalogInternalController`: `/internal/v1/catalog-version` and `/internal/v1/products`
- `CatalogRepository` and the seed migrations
- Projection loaders in the other services consume it (`CatalogProjectionLoader`)

---

# Scan Path in Checkout

- `CheckoutController` → `ScanService.scan`
- `ProductLookup`: prices from the local projection, with Catalog as the fallback
- Basket update and outbox row written in one local transaction
- `409 TRANSACTION_BUSY` while a completion is in progress

---

# Completion: Checkout Side

- `CompletionService.complete`: freezes the basket into a `CompletionAttempt`
- `AttemptDriver`: steps through the `AttemptState` machine
- `HttpInventoryClient`: reserve and commit calls with timeouts
- `ReceiptAssembler`: per-attempt receipts

---

# Completion: Inventory Side

- `ReservationController` → `ReservationService`: reserve, commit, cancel, get
- Idempotent by `attemptId`, with lock-retry handling
- `SaleApplier`: moves committed quantities from `sale_queue` into physical stock
- Migrations `V6__reservations.sql` and `V8__sale_queue.sql`

---

# Recovery and Fault Injection

- `RecoveryWorker`: scheduled scan of unresolved attempts, with leases
- `RecoveryProperties`: retry, backoff and cancel thresholds
- `FaultInjector` and `docker-compose.faults.yml` (Toxiproxy) for the recovery tests

---

# Accepted Scans: Outbox to Kafka

- `V6__accepted_scan_outbox.sql`: the outbox table
- `OutboxRelay`: a single relay per database (MySQL named lock), ordered publishing
- `AcceptedScanEventMapper` and `KafkaProducerConfig`

---

# Analytics Pipeline

- `AcceptedScanListener` → `ScanPipeline.process`
- `IngestFilter` → `WindowFilter` → `RankAndPublishFilter`
- `PopularItemsController`: reads the published snapshot
- `IngestStatusController`: ingest progress and consumer lag

---

# Read Models in Inventory

- `LowStockController` and `LowStockRepository`
- `CatalogNameProjection` for item names

---

# Data Ownership in Code

- Flyway migrations per service under `db/migration`
- `V*__private_store.sql`: removing the old cross-schema objects
- Health indicators: readiness depends only on the service's own database

---

# Tests

- Unit and Testcontainers tests per module (`AttemptDriverTest`, `ReservationServiceIT`, `OutboxRelayIT`, `ScanIngestIT`)
- Contract tests against the shared fixtures
- System tests in `integration-tests` (`PartialFulfillmentTest`, `ConcurrentLastUnitTest`, `LostResponseTest`)
- Architecture tests: `ModuleBoundaryTest` and `PrivateStoreAccessTest`

---

# Operations and Evidence

- Operator endpoints under `/internal/v1/ops/**`
- `scripts/reconcile.sh`: checks R-1 to R-6
- `scripts/run-measurement.sh` and the outputs in `reports/`

---

# Q&A
