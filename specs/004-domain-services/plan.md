# Implementation Plan: Independently Deployable Checkout Microservices

**Branch**: `004-domain-services` (feature folder; working Git branch `microservice-new`) |
**Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-domain-services/spec.md` (clarified 2026-10-23)

**Governance**: [Constitution v3.0.0](../../.specify/memory/constitution.md)

## Summary

Migrate the database-coordinated Catalog, Checkout, Inventory and Analytics services to
independently deployable microservices. Each service gets a private MySQL instance, and services
communicate only through versioned HTTP APIs and a Kafka event stream. The API gateway keeps the
seven public operations and becomes a transparent router.

- **Checkout orchestrates completion** as a durable saga of local transactions:
  freeze → Inventory reserve → prepare receipt intent → Inventory commit → finalize. A stable
  attempt identity, idempotent Inventory commands, forward-only recovery after the receipt intent
  is durable, and a bounded 5 s public deadline mapped to `503 SERVICE_UNAVAILABLE` support
  partial purchases with per-attempt receipts, without overselling or double-selling.
- **Accepted scans** go to a transactional outbox that a Checkout relay publishes to Kafka.
  Analytics ingests idempotently, assigns canonical order on first ingest, and publishes complete
  `ingest → window → rank-and-publish` snapshots.
- **Catalog data** reaches Checkout, Inventory and Analytics through versioned local projections,
  keeping the scan path free of synchronous Catalog calls.
- **Baseline first**: the current implementation is measured with adequate stock and bottleneck
  observations, then the redesign is compared against it under the same host and workload.

Decisions and rejected alternatives are in [research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**:
- Spring Boot 3.4.3 (Spring MVC, Spring JDBC, actuator/Micrometer)
- Spring for Apache Kafka (Boot-managed 3.3.x)
- Flyway 10.22.0 (MySQL)
- MySQL Connector/J
- JDK `HttpClient` through `JdkClientHttpRequestFactory`

**Storage**:
- Four private MySQL 8.4 instances: `catalog-db`, `checkout-db`, `inventory-db`, `analytics-db`.
  Each holds one owner database and one owner account, with no cross-instance accounts or grants.
- Apache Kafka 3.8.1 (single KRaft node) holds `checkout.accepted-scans.v1`.

**Testing**:
- JUnit 5 and Spring Boot Test for unit and slice tests.
- Testcontainers 1.20.4 (MySQL, Kafka, Toxiproxy) for service integration tests.
- Docker Compose system tests for the end-to-end, outage and boundary scenarios.
- Contract-fixture tests for internal APIs and events.
- The unchanged load client for normal and stress runs.
- Reconciliation SQL run per owner database, then combined by a script.

**Target Platform**: Developer workstation or Linux host with JDK 21, Maven 3.9, and Docker with
Compose v2. Baseline and redesign are measured on the same host.

**Project Type**: Multi-module web-service system: four domain microservices plus a
transport-only API gateway, each packaged as an executable JAR and container image.

**Performance Goals** (SC-008, SC-009):
- Normal workload (10 stations, 60 s) completes with zero operation errors.
- Across 3 baseline and 3 redesign runs (200 stations, 180 s), median scan p95 is at least 10%
  lower than baseline.
- Successful scan throughput is not lower than baseline.
- Median start and complete p95 do not regress.
- Recovery leaves nothing unexplained within 60 s of dependencies becoming healthy (SC-004).

**Constraints**:
- Public OpenAPI behavior is preserved except for the documented clarification amendments
  ([public-api-amendment.md](contracts/public-api-amendment.md)).
- The load client and mock server stay unmodified.
- No cross-store access of any kind.
- No local transaction or row lock is held across a remote call.
- The public completion deadline is 5 s, below the client's 10 s.
- Analytics stays off the synchronous path.
- Prices and totals are exact cents (`BIGINT` cents internally, two-decimal JSON numbers
  publicly).

**Scale/Scope**:
- 5 deployables, 4 private databases, 1 broker topic, 3 internal HTTP contracts and 1 event
  contract.
- 2,000-SKU catalog; baskets of 1–20 units.
- Stress volume of about 280k–600k scans per run, with ≥ 10,000 scans in the outage test.

## Constitution Check

*GATE: Evaluated before Phase 0 research. Re-checked after Phase 1 design (results in the last
column).*

| Constitution requirement | Design response | Pre | Post |
|---|---|---|---|
| **I.** Public contract authoritative; completion reports only confirmed fulfillment; timeout and retry documented in the existing contract | Seven routes and body schemas unchanged. A 200 is sent only after `FINALIZED`. The 409/503 outcomes are documented as amendments A1–A6 with no new structures. No pending response or async API. | Pass | Pass (A6 approved 2026-10-24) |
| **II.** Test harness unchanged; runs record version, topology and data settings | Load client used only through documented flags. Each report pairs with an observations file recording commit, topology, pool sizes, stock seed and timeouts (R14). | Pass | Pass |
| **III.** Four separately deployable services with private stores, credentials and migrations | One Maven module, JAR and image per service. Four MySQL instances with owner-only accounts. Flyway per service. Cross-schema views, procedure and grant script removed (R2). | Pass | Pass |
| **III.** Communication only through versioned APIs or durable messages | Internal HTTP `v1` contracts for Catalog and Inventory, Kafka `v1` event for scans, and the `service-contracts` module containing transport records only (R16). | Pass | Pass |
| **III.** Gateway is routing and translation only | The gateway forwards status and body unchanged and maps unreachable upstreams to 503. No datasource, no business rules, no completion logic (R12, R15). | Pass | Pass |
| **III.** Checkout owns the completion workflow; Analytics consumes asynchronously | Saga lives in Checkout. Analytics consumes Kafka only. Pipeline is three adjacent filters (R5, R8). | Pass | Pass |
| **III.** Catalog projection has a freshness, versioning and recovery policy | `catalogVersion` poll every 15 s, 60 s staleness bound, miss and stale rules, rebuild by reload (R6, R7). | Pass | Pass |
| **IV.** Reservations at completion; `stock ≥ reserved ≥ 0`; only commit decrements | Inventory reserve and commit use local row locks in SKU order, conditional updates and CHECK constraints (R11). | Pass | Pass |
| **IV.** Partial fulfillment; unavailable quantities stay in the basket and are identified to the customer | Available units are bought, and the rest stay in the open basket. They are identified through the transaction's remaining `itemCount`/`runningTotal`/`OPEN` status and the receipt difference, with no shortage payload, per the user's clarification. See Complexity Tracking. | Pass (justified) | Pass (justified) |
| **IV.** Saga: freeze, stable attempt id, reserve → durable intent → commit → finalize, idempotency, unknown-outcome handling, restart recovery, visible failures | `completion_attempts` state machine, `active_attempt_id` freeze, idempotent Inventory commands keyed by `attemptId`, cancel tombstones, forward-only after `COMMITTING`, recovery worker with leases and backoff, `needs_attention`, operator endpoint (R8–R10, [data-model.md](data-model.md)). | Pass | Pass |
| **IV.** Reconciliation verifies the inventory invariant and no unexplained attempts or reservations | `reconcile.sh` gathers each owner's export through its own credentials and compares them (quickstart V9). | Pass | Pass |
| **V.** Timestamped normal and stress reports; percentiles; differences declared | Three runs each, before and after, medians in `COMPARISON.md`, declared provisioning differences (R14). | Pass | Pass |
| **V.** Baseline before migration with bottleneck observations | Baseline tag plus an instrumentation-only commit; observation capture covers service time, database waits, deadlocks, pool waits and resource use (R14). | Pass | Pass |
| **V.** Document artifacts, stores, contracts, saga, outbox, filter order and failure evidence | One README entry point plus this feature's contracts, and recovery evidence in reports (R19). | Pass | Pass |
| **Constraints**: outbox in the same local transaction; durable broker; idempotent receivers that commit checkpoints with effects; acknowledge after processing; documented ordering | Scan and outbox in one transaction. Kafka with idempotent producer. Analytics dedupe, sequence and snapshot commit together before ack (R4, R5, [accepted-scan-event.md](contracts/accepted-scan-event.md)). | Pass | Pass |
| **Constraints**: timeouts and bounded retries; no lock across a remote call; relay and consumer resume without fixed-oldest-batch starvation | R9 budget table. Each saga step is its own short local transaction. Relay selects unpublished rows; consumer offsets follow commits. | Pass | Pass |
| **Constraints**: shared code without domain logic; independent packaging, health, migrations and graceful shutdown | `service-contracts` only, `shared-db` removed. Actuator readiness covers own resources only. Graceful shutdown drains (R16, R17). | Pass | Pass |
| **Quality gates 1–7** | Each gate maps to quickstart scenarios: gate 1 → V1, V2; gate 2 → V2, V3; gate 3 → V4; gate 4 → V10; gate 5 → V5–V7, V9; gate 6 → V8; gate 7 → V11. | Pass | Pass |

**Gate result**: PASS. No unjustified violations.

1. Amendment **A6**, a general `503` for unavailable owners or dependencies, was **approved by
   the user on 2026-10-24**. All amendments A1–A6 are approved and must be applied to the
   OpenAPI YAML as the first implementation task (FR-034).
2. The constitution IV wording on "identify in the customer-facing result" is resolved by the
   spec's clarification. A PATCH clarification of the constitution is recommended.

## Project Structure

### Documentation (this feature)

```text
specs/004-domain-services/
├── spec.md
├── plan.md                         # this file
├── research.md                     # Phase 0 decisions R1–R20
├── data-model.md                   # Phase 1 entities, state machines, invariants
├── quickstart.md                   # Phase 1 validation scenarios V0–V11
├── contracts/
│   ├── README.md                   # index and versioning rules
│   ├── public-api-amendment.md     # A1–A6 OpenAPI amendments and error catalog
│   ├── catalog-internal-api.md     # Catalog v1 snapshot/lookup API
│   ├── inventory-internal-api.md   # Inventory v1 reservation/low-stock API
│   ├── checkout-operations-api.md  # Checkout operator/recovery visibility API
│   └── accepted-scan-event.md      # Kafka checkout.accepted-scans.v1 event
├── checklists/requirements.md
└── tasks.md                        # Phase 2 ($speckit-tasks, not created here)
```

### Source Code (repository root)

```text
pom.xml                                   # reactor (monolith, domain-services)
spec/self-checkout-openapi.yaml           # amended first (A1–A6), v1.2.0
domain-services/
├── pom.xml                               # modules: service-contracts, 4 services, gateway, integration-tests
├── service-contracts/                    # NEW: versioned transport records + JSON fixtures only
│   └── src/main/java/edu/northeastern/cs6510/contracts/{catalog,inventory,events}/v1/
├── catalog-service/
│   ├── src/main/java/edu/northeastern/cs6510/catalog/          # api/, internal/ (v1 snapshot API), CatalogRepository
│   └── src/main/resources/db/migration/                        # V3 drops view; adds catalog_metadata (version)
├── checkout-service/
│   ├── src/main/java/edu/northeastern/cs6510/checkout/
│   │   ├── api/                 # public controller + ApiError advice
│   │   ├── scan/                # scan service (projection lookup, busy check, outbox write)
│   │   ├── completion/          # saga orchestrator, attempt repository, state machine
│   │   ├── recovery/            # recovery worker, leases, operator endpoint
│   │   ├── inventory/           # Inventory v1 HTTP client (timeouts, retries)
│   │   ├── catalog/             # catalog projection loader + in-memory cache
│   │   ├── outbox/              # relay -> Kafka
│   │   └── fault/               # FaultInjector (fault-injection profile only)
│   └── src/main/resources/db/migration/                        # V4+ attempts, receipts by attempt, outbox, projection
├── inventory-service/
│   ├── src/main/java/edu/northeastern/cs6510/inventory/        # reservation/, lowstock/, catalog/ (name projection)
│   └── src/main/resources/db/migration/                        # V5+ drop procedure/view, physical/reserved, reservations, movements
├── analytics-service/
│   ├── src/main/java/edu/northeastern/cs6510/analytics/        # ingest/, window/, rank/ (filters), api/, catalog/
│   └── src/main/resources/db/migration/                        # V3+ ingested_scans, ingest_state, snapshots + pointer
├── api-gateway/                          # pass-through routing, 503 on unreachable upstream, no datasource
├── deployment/
│   ├── README.md                         # single documentation entry point (SC-011)
│   ├── ARCHITECTURE.md                   # rewritten for private stores
│   ├── docker-compose.yml                # 4 MySQL, Kafka, 5 apps (+ toxiproxy under the `faults` profile)
│   ├── mysql/{catalog,checkout,inventory,analytics}/init/       # owner-only DB + account per instance
│   ├── provisioning/measured-stock.sql   # 1,000,000 units/SKU via Inventory owner
│   ├── scripts/                          # start, readiness, capture-observations, reconcile, reset
│   └── reports/                          # baseline/, redesign/, COMPARISON.md, historical/ (2026-10-16)
└── integration-tests/
    ├── src/test/java/edu/northeastern/cs6510/
    │   ├── boundaries/   # PrivateStoreAccessTest, ModuleBoundaryTest (replaces NoDirectDomainCallsTest)
    │   ├── contract/     # public contract + internal fixture compatibility
    │   ├── completion/   # partial, zero-stock, busy, concurrency, interruption, lost response
    │   ├── analytics/    # 1,500-scan ranking, replay/duplicates, outage ≥10,000 scans
    │   └── deployment/   # independent startup/readiness per service
    └── scripts/          # reconcile-*.sql per owner, combine-reconciliation
```

**Structure Decision**: Keep the existing Maven reactor under `domain-services/` with one
executable Spring Boot JAR and Dockerfile per deployable. Remove the unused `shared-db` module
(F11). Add `service-contracts` as the only module that domain services may share; it holds
transport records and fixtures and has no persistence. Each service's internal packages split by
responsibility (scan, completion, recovery, outbox, projection) so the saga and its recovery can
be tested in isolation. `deployment/` owns topology, provisioning, measurement and reconciliation
tooling.

## Phase Outline (for `$speckit-tasks`)

Ordering constraints that tasks must preserve:

1. **Contract amendment first**: apply the approved A1–A6 to `spec/self-checkout-openapi.yaml`,
   and add superseded notes to older docs (R19, R20).
2. **Baseline before any code change**: build the instrumentation-only commit and baseline tag,
   apply measured stock, run 3 normal and 3 stress, capture observations, commit reports under
   `reports/baseline/` (R14). Move the 2026-10-16 reports to `reports/historical/` with a label.
3. Private-store topology and owner migrations; remove cross-store objects.
4. `service-contracts` and the Catalog v1 API, then the projections in Checkout, Inventory and
   Analytics.
5. Inventory reservations, then the Checkout saga, then recovery, operator view and fault
   injection.
6. Scan outbox, relay, Kafka, then the Analytics consumer and snapshot pointer.
7. Gateway pass-through and error mapping.
8. Boundary, contract, completion, analytics and deployment tests, then reconciliation.
9. Redesign measurement runs, `COMPARISON.md`, and the documentation entry point.

## Complexity Tracking

| Item | Why needed | Simpler alternative rejected because |
|---|---|---|
| Four MySQL instances plus Kafka (6 infrastructure containers vs 1) | Constitution III/Constraints: private stores and a durable broker | One shared instance with grants keeps resource contention and makes ownership a matter of grant discipline |
| Local catalog projections in three services | Keeps the scan path (SC-009 target) and read views free of synchronous Catalog calls, with a provable freshness rule | Per-scan Catalog calls add a network hop to every scan and fail scans during Catalog restarts |
| Attempt saga with leases and a recovery worker | FR-010–FR-019: durable recovery across private stores | A single transaction across stores is prohibited; without leases, replicas or joined requests could drive one step twice |
| **Constitution IV "identify unavailable quantities in the customer-facing result"** without a shortage field | The user's 2026-10-23 clarification forbids new shortage fields, objects or messages. Unavailable units stay visible as the open transaction's remaining basket (`GET /transactions/{id}` → `status: OPEN`, `itemCount`, `runningTotal`) and as the difference from the receipt. | Adding a shortage payload contradicts the approved clarification. **Recommended**: PATCH-amend constitution IV to say "identifiable through the retained basket" to remove the ambiguity. |
| **Amendment A6: general `503 SERVICE_UNAVAILABLE`** (approved by the user 2026-10-24) | FR-029 forbids disguising dependency failure, and independent deployment means any owner can be down. Today these cases yield an undocumented 500. | Narrowing 503 to completion would leave scan-time "Catalog unverifiable", low-stock or popular "no projected name", and "owner unreachable" to undocumented 500s. |
