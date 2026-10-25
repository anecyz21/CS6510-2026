# Research: Independently Deployable Checkout Microservices

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-10-24

This file resolves every planning decision the specification left open (Assumptions: "physical
provisioning, internal contract shapes, delivery technology, product lookup strategy, retry
budgets, and scan-order assignment are planning decisions"). Each entry records the decision,
why it was chosen, and what was rejected.

## Findings from the current implementation

The pre-migration code at commit `8f40554` (branch `microservice-new`) is the baseline the
redesign replaces. Reading it surfaced facts that constrain the plan:

| # | Finding | Location | Consequence for the plan |
|---|---|---|---|
| F1 | Analytics polls `checkout.accepted_scan_outbox ORDER BY scan_id LIMIT 500` and never advances past it; outbox rows are never marked. Only the first 500 accepted scans are ever ingested. | `AnalyticsApplication.OutboxConsumer` | Delivery must advance on durable progress (FR-024, SC-007). Baseline popular-items results are not valid evidence. |
| F2 | Analytics assigns `sequence_number` with `MAX()+1` outside a lock. | same | Sequence assignment must be serialized inside the consumer's local transaction (R5). |
| F3 | Snapshot publication deletes and reinserts rows for the same `window_end`, so a reader can see a half-built ranking. | same | Publish new rows under a new key, then flip a pointer (R5, FR-023 / US4-5). |
| F4 | Low-stock and popular-items responses put the SKU in `name`. | `InventoryApplication`, `AnalyticsApplication` | Inventory and Analytics need product names through a supported boundary (R7, FR-026). |
| F5 | No service maps failures to the `ApiError` body; the gateway's `RestClient` turns every upstream 4xx into a 500. | all services, `HttpCheckoutClient` | Error mapping and transparent gateway pass-through are part of this feature (R12, FR-006). |
| F6 | Completion runs as one Checkout transaction that calls `inventory.fulfill_checkout`, and one `receipts` row per transaction (PK `transaction_id`) accumulates every partial purchase. | `CheckoutService.complete`, `V3__receipts.sql` | Replaced by the attempt saga (R8) and per-attempt receipts (R13). |
| F7 | All four schemas share one MySQL instance, with cross-schema grants applied by `grant-cross-domain-interfaces.sh`. | `deployment/` | Replaced by four private instances with no cross grants (R2). |
| F8 | The 2026-10-16 stress report shows 35 SKUs at `currentStock: 0`. The load client samples SKUs Zipf(1/rank) over 2,000 items, so the top SKU receives about 12.2% of scans (1/H₂₀₀₀ ≈ 1/8.18), about 34,000 of the 281,724 stress scans, against a seed of 1,000. | `ItemSampler`, `report-20261016-044039.json` | Those runs did not have adequate stock: completions were silently partial. They are historical, not comparable baseline evidence (R14, SC-008). |
| F9 | Checkout's Hikari pool is the Spring default (10) under 200 concurrent stations, and scan retries deadlock victims up to 8 times. | `application.yml`, `CheckoutService` | The baseline must record connection-wait and retry observations before tuning is attributed to architecture (R14, FR-030). |
| F10 | `deployment/services.env` is untracked and is not covered by `.gitignore` (`.env` / `.env.*` only). | repository root `.gitignore` | Add an ignore rule before credentials are placed there (FR-028). |
| F11 | `shared-db` (`DatabaseAccess`) is not referenced by any service. | `shared-db/` | Remove the module; add a transport-only `service-contracts` module instead (R16). |

---

## R1. Runtime stack

- **Decision**: Keep Java 21, Spring Boot 3.4.3 (Spring MVC, Spring JDBC/`JdbcTemplate`,
  `TransactionTemplate`), Flyway 10.22.0 and MySQL Connector/J. Add
  `spring-boot-starter-actuator` (health groups, Micrometer metrics) to every deployable and
  `spring-kafka` (Boot-managed 3.3.x) to Checkout and Analytics. Use the JDK `HttpClient` via
  `JdkClientHttpRequestFactory` for all internal HTTP, configured explicitly (R9).
- **Rationale**: The reactor already builds with these versions. Changing the framework would
  confound the performance comparison (SC-009) with framework effects.
- **Alternatives considered**: Spring Cloud Gateway (reactive stack, new runtime model for one
  thin router; rejected); JPA (adds a persistence abstraction the services do not need and hides
  lock behavior that matters for FR-012).

## R2. Private data stores and credentials

- **Decision**: Four MySQL 8.4 containers (`catalog-db`, `checkout-db`, `inventory-db`,
  `analytics-db`), one per service, each initialized with only its owner database and owner
  account. Host ports 3311–3314 are for operators and tests only. Credentials come from
  `deployment/services.env` (gitignored), with `services.env.example` committed. Each service
  runs only its own Flyway migrations. Cross-schema views (`catalog.active_items`,
  `checkout.accepted_scan_outbox`, `analytics.published_popularity`, `inventory.low_stock`),
  the `fulfill_checkout` procedure and `grant-cross-domain-interfaces.sh` are dropped or deleted.
  A service's account does not exist on any other service's instance, so cross-store access fails
  at authentication rather than by grant omission.
- **Rationale**: The constitution names separate MySQL instances as the initial design. Separate
  instances make "denied" true by construction (SC-002) and stop Analytics polling and Inventory
  locking from competing in Checkout's buffer pool and redo log.
- **Alternatives considered**: One instance with per-schema accounts (permitted by "same engine
  does not require sharing an instance", but leaves shared resource contention and makes
  accidental grants possible; rejected); one database per service on a managed cloud
  (out of scope for local evidence).
- **Measurement note**: All four instances run on the same host as the baseline's single
  instance, with no per-container CPU or memory limits in either topology. The total budget is
  the host, recorded in each report. The difference in database process count is a declared
  provisioning difference (constitution V), not hidden.

## R3. Durable broker for accepted scans

- **Decision**: Apache Kafka 3.8.1 (`apache/kafka:3.8.1`, single KRaft node, persistent volume).
  Topic `checkout.accepted-scans.v1` with 1 partition, replication factor 1,
  `min.insync.replicas=1`, retention 7 days. Producer: `enable.idempotence=true`, `acks=all`.
  Consumer group `analytics-scan-ingest`, `enable.auto.commit=false`, manual acknowledgment after
  the local commit (R5).
- **Rationale**: A single partition gives one total order per topic, so the relay's publish order
  is the order Analytics receives. Durable retention plus resettable offsets make the replay test
  (SC-006) a configuration action rather than custom code. Throughput far exceeds the ~1,600
  scans/s stress rate.
- **Alternatives considered**: RabbitMQ quorum queue (durable, but replay requires republishing
  from the outbox and redelivery reorders messages); Redpanda (Kafka-compatible, but a less
  common local image for course reviewers); MySQL table polled by Analytics (violates "a durable
  broker MUST deliver accepted-scan events" and private ownership).

## R4. Checkout transactional outbox and relay

- **Decision**: Each accepted scan inserts one `accepted_scan_outbox` row in the same local
  transaction as the basket upsert (FR-020). A relay inside Checkout:
  1. holds a MySQL named lock `GET_LOCK('checkout.scan-relay', 0)` so one relay runs per
     Checkout database even with replicas;
  2. selects `WHERE published_at IS NULL ORDER BY outbox_id LIMIT 500`;
  3. sends the batch, waits for every broker acknowledgment, then sets `published_at` for exactly
     the acknowledged rows in one statement;
  4. loops immediately while a full batch was found and otherwise sleeps `relay.poll-ms` (200 ms).
  Published rows older than `relay.retention` (24 h) are purged in small batches.
- **Rationale**: Per-row marking means a row whose `AUTO_INCREMENT` id committed late is still
  picked up on the next pass. A high-water-mark id cannot guarantee that. Selecting only unmarked
  rows makes progress advance past processed batches (FR-024, no fixed-oldest-batch starvation).
  Broker outages leave rows pending and visible as `checkout_outbox_pending` (FR-025).
- **Alternatives considered**: Debezium CDC (adds Kafka Connect and binlog permissions);
  publish-after-commit without an outbox (loses events on crash; violates FR-020); a watermark
  cursor (can skip late-committing rows).
- **Duplicate behavior**: A crash between broker acknowledgment and marking republishes the
  batch. Consumers deduplicate on `eventId` (R5).

## R5. Analytics ingestion, canonical order and publication

- **Decision**: A Kafka batch listener processes each poll in one Analytics local transaction:
  1. take a row lock on `ingest_state` (single row holding `last_sequence`);
  2. for each record in partition order, `INSERT IGNORE` into `ingested_scans(event_id, …)`.
     When a row is inserted, assign `sequence_number = last_sequence + 1` and advance
     `last_sequence`. Duplicates are ignored, so a sequence number is never reused;
  3. for every publication boundary crossed by this batch (sequence 1, then every 500), compute
     the window `[max(1, end − 999), end]`, rank by `count DESC, sku ASC`, write it under the new
     `window_end`, and move `published_snapshot.current_window_end` to it;
  4. commit, then acknowledge the Kafka offsets.
  Readers read `published_snapshot` and that snapshot's rows in one read-only transaction.
- **Canonical order**: Order of first durable ingest, which equals relay publish order on the
  single partition. Once assigned, a sequence number never changes, so replays and duplicates
  cannot move window boundaries (FR-024, SC-006).
- **Startup partial window**: Preserved from the current behavior. The first publication occurs
  at sequence 1 (window `[1,1]`), then at 500, 1,000, 1,500, and so on, with `windowStart = max(1, end − 999)`.
  Before any publication, popular items returns the documented empty result (US4-6).
- **Rationale**: Committing dedupe, sequence and snapshot together before acknowledging gives one
  business effect per scan per window (FR-022). Writing under a new key and then flipping a
  pointer means readers see a complete old or new ranking (US4-5).
- **Alternatives considered**: Kafka offset as the sequence (relay duplicates receive new
  offsets and would be counted twice without the dedupe table; offsets also reset if the topic is
  recreated); Checkout-assigned global sequence (`AUTO_INCREMENT` commit order differs from id
  order under concurrency, so gaps are temporarily invisible).
- **Pipeline shape**: `IngestFilter → WindowFilter → RankAndPublishFilter`, each a separate
  class with one responsibility, connected only to its neighbor and run inside the same local
  transaction (constitution III). Graceful shutdown stops the listener container, completes the
  in-flight batch, and leaves unacknowledged offsets for redelivery.

## R6. Checkout's product lookup (FR-009)

- **Decision**: Checkout keeps a local, versioned **catalog projection** (`catalog_projection`
  table, cached in memory) loaded from Catalog's internal snapshot API
  ([catalog-internal-api.md](contracts/catalog-internal-api.md)):
  - *Load*: on startup and whenever Catalog reports a different `catalogVersion`, page through
    `GET /internal/v1/products` and replace the projection atomically (stage table, then swap
    `projection_state.catalog_version`).
  - *Freshness*: poll `GET /internal/v1/catalog-version` every 15 s. The projection is
    **current** if the last successful version check is no older than 60 s
    (`catalog.projection.max-staleness`).
  - *Scan behavior*:
    - SKU found → accept at the projected price, which is captured into the basket line.
    - SKU absent from a **current** projection → `404 SKU_NOT_FOUND`.
    - SKU absent and projection **stale or never loaded** → one direct
      `GET /internal/v1/products/{sku}` (300 ms timeout). Found → accept and upsert it into the
      projection. 404 → `SKU_NOT_FOUND`. Unreachable → `503 SERVICE_UNAVAILABLE`.
  - *Rebuild*: deleting the projection rows triggers a full reload; the projection is derived data
    and never authoritative.
- **Rationale**: The scan path is the SC-009 target. A per-scan network call to Catalog adds
  latency and makes scans fail whenever Catalog restarts. Catalog data is fixed by its seed
  migration in this feature (dynamic catalog administration is out of scope), so a version
  number makes staleness detectable. Captured prices never change after the scan (edge case).
- **Alternatives considered**: Synchronous per-scan lookup (latency, availability coupling);
  Catalog change events on Kafka (no catalog writes exist to emit; adds a topic without a
  producer workflow); unversioned TTL cache (cannot prove a miss means "not in catalog").

## R7. Product names for low-stock and popular items (FR-026)

- **Decision**: Inventory and Analytics each keep a name-only projection `(sku, name,
  catalog_version)` loaded through the same Catalog snapshot API and version poll as R6. Low-stock
  alerts and popularity snapshots use the projected name. If a SKU has no projected name, for
  example because Catalog has never been reachable since first start, the view returns
  `503 SERVICE_UNAVAILABLE`. It never substitutes the SKU (F4). Popularity snapshots store the
  name at publication time.
- **Rationale**: Keeps both read views free of synchronous calls to Catalog while giving them a
  real name, with the same documented freshness policy.
- **Alternatives considered**: Gateway composition (makes the gateway join domain data, which
  goes beyond FR-005's "contract translation"); per-request Catalog calls from Inventory
  (couples the low-stock view's availability to Catalog).

## R8. Completion saga

- **Decision**: Checkout orchestrates a durable per-attempt saga. States and transitions are
  in [data-model.md](data-model.md#completion-attempt-state-machine). Summary:
  1. **Freeze** (Checkout local tx): lock the transaction row, validate, copy the basket into
     `completion_attempt_lines`, set `checkout_transactions.active_attempt_id`, state
     `RESERVING`.
  2. **Reserve** (Inventory, idempotent on `attemptId`): reserve `min(requested, physical −
     reserved)` per SKU in one local tx.
  3. **Prepare** (Checkout local tx): record reserved quantities as the receipt intent → state
     `COMMITTING`. If every reserved quantity is zero → state `REJECTED`, unfreeze, respond
     `409 INSUFFICIENT_STOCK`.
  4. **Commit** (Inventory, idempotent): decrement physical stock and reserved quantity together,
     and write movements referencing `attemptId`.
  5. **Finalize** (Checkout local tx): write the per-attempt receipt, subtract purchased
     quantities from the basket, complete the transaction if the basket is empty, clear
     `active_attempt_id`, state `FINALIZED`.
- **Point of no return**: Once an attempt reaches `COMMITTING` (receipt intent durable), it only
  moves forward. Recovery retries commit and never cancels. Before `COMMITTING`, recovery may
  cancel. Inventory never expires reservations, so a reservation held by a `COMMITTING` attempt
  always remains committable, and cancel/commit cannot race to contradictory outcomes (FR-017).
- **Cancel-before-reserve race**: Inventory's cancel on an unknown `attemptId` writes a
  `CANCELLED` tombstone. A late reserve for that id returns the tombstone and reserves nothing.
  Commit and cancel on the same reservation serialize on its row lock. Whichever commits first
  wins; the other returns `409` with the winning state.
- **Rationale**: Implements constitution IV's prescribed order (reserve → durable receipt intent
  → commit → finalize). It never holds a local transaction open across a remote call (FR-018),
  and every stock movement references a durable attempt (FR-019).
- **Alternatives considered**: Choreography over Kafka (makes the synchronous public completion
  wait on asynchronous events and adds a second broker dependency to the checkout path);
  two-phase commit/XA (prohibited: shared transaction across stores); reservation TTL (requires
  coordinated expiry against commit, which the forward-only rule avoids).

## R9. Timeouts, retries and recovery budgets

All values are configuration properties with these defaults, recorded in every measured report.

| Setting | Default | Notes |
|---|---|---|
| `checkout.completion.request-deadline` | 5 s | Public completion returns `503 SERVICE_UNAVAILABLE` at this point if the outcome is unconfirmed. It is below the load client's 10 s `--requestTimeout`. |
| Inventory client connect / read timeout | 300 ms / 1,500 ms | Per call |
| In-request retries per Inventory call | up to 3, backoff 100 → 200 → 400 ms | Retries stop at the request deadline. Every retry reuses the same `attemptId`. |
| `checkout.recovery.scan-interval` | 1 s | Recovery worker picks attempts with `next_retry_at ≤ now` and an expired or empty lease |
| Recovery backoff | 250 ms × 2ⁿ, cap 5 s | The cap keeps recovery within the 60 s bound (SC-004) |
| `checkout.recovery.cancel-after` | 30 s | Unconfirmed reserve older than this → `CANCELLING` (only before `COMMITTING`) |
| `checkout.recovery.attention-after` | 20 failed steps | Sets `needs_attention`. Retrying continues at the cap, and the attempt appears in the operator view. |
| Attempt lease | 10 s | `lease_owner`/`lease_until` stop two drivers or Checkout replicas from working the same attempt step concurrently |
| Gateway → service read timeout | 8 s | Longer than Checkout's deadline, so the client receives Checkout's 503 rather than a gateway timeout |
| Catalog snapshot client | 300 ms connect / 2 s read | Off the request path except for the R6 stale-miss lookup |

- **Rationale**: The public deadline is finite and documented (FR-016). In-request retries stay
  within it, and background retries are bounded per step, with a visible alert state (FR-018).
- **Alternatives considered**: Waiting for Inventory recovery inside the request (rejected by the
  clarification); unbounded immediate retry (risks a retry storm against a recovering Inventory).

## R10. Basket freeze, `TRANSACTION_BUSY` and joined completions

- **Decision**: `checkout_transactions.active_attempt_id` is the busy flag.
  - Scan transaction: `SELECT status, active_attempt_id … FOR SHARE`. A non-null id returns
    `409 TRANSACTION_BUSY` immediately, with no basket write and no outbox row (FR-011).
  - Freeze takes `FOR UPDATE` on the same row, so a scan either commits before the freeze (and is
    in the snapshot) or sees the busy flag.
  - A completion arriving with a non-null `active_attempt_id` **joins**: it drives the same
    attempt with the same idempotent steps (conditional state updates mean only one driver
    advances each step) until a terminal state or the request deadline. It then returns that
    attempt's outcome: `200` receipt, `409 INSUFFICIENT_STOCK`, or `503`.
  - An attempt `CANCELLED` by recovery leaves the basket open and unfrozen. A joined request
    observing it returns `503 SERVICE_UNAVAILABLE`, and the next completion creates a new attempt.
- **Rationale**: Satisfies the clarified busy behavior and "at most one unresolved attempt per
  transaction" with one row lock and no queue.
- **Alternatives considered**: Version column on basket lines (allows a concurrent scan to change
  the basket during reservation); in-memory locks (lost on restart, wrong with replicas).

## R11. Inventory concurrency control

- **Decision**: Reserve and commit each run as one Inventory local transaction:
  1. `SELECT … FROM inventory_positions WHERE sku IN (…) ORDER BY sku FOR UPDATE`, which locks
     rows in deterministic order to prevent deadlock cycles;
  2. compute grants;
  3. apply conditional updates such as
     `UPDATE … SET reserved_stock = reserved_stock + ? WHERE sku = ? AND physical_stock - reserved_stock >= ?`.
  `CHECK (physical_stock >= reserved_stock AND reserved_stock >= 0)` stays as a last line of
  defense. Deadlock or lock-wait victims retry up to 3 times inside the handler, which is safe
  because the operation is keyed by `attemptId`. Unknown SKUs (no position) reserve 0.
- **Rationale**: Enforces `stock ≥ reserved ≥ 0` locally (constitution IV) without long locks.
  Each lock lives for one short transaction.
- **Alternatives considered**: Optimistic version retries per SKU (more round trips for 20-item
  baskets on hot Zipf SKUs); `SERIALIZABLE` isolation (more gap locking).

## R12. Public error mapping, gateway pass-through and dependency failures

- **Decision**:
  - Each domain service maps domain exceptions to `ApiError {error, message}` with a
    `@RestControllerAdvice`, using the codes in
    [public-api-amendment.md](contracts/public-api-amendment.md#error-catalog-after-amendment).
  - The gateway forwards upstream status and body unchanged. Validation it already performs
    (`MISSING_STATION_ID`, `MISSING_SKU`, `INVALID_LIMIT`) keeps its codes. Upstream connection
    refusals or timeouts become `503 SERVICE_UNAVAILABLE` in `ApiError` format.
- **Amendment A6 (approved by the user 2026-10-24)**: The spec authorizes `503 SERVICE_UNAVAILABLE` only for
  unconfirmed completion. FR-029 also forbids disguising dependency failure as a missing product,
  and constitution III makes each operation depend on a separately deployed owner. The plan
  therefore proposes documenting `503 SERVICE_UNAVAILABLE` (existing `ApiError` body, no new
  fields) on all seven operations, limited to "the owning service or a required dependency is
  unavailable; no business effect was confirmed." Today these cases produce an undocumented
  `500`. This is recorded as amendment **A6** and in Complexity Tracking. The user approved it in
  full during planning, which satisfies FR-034.
- **Alternatives considered**: Leaving undocumented 500s (violates FR-029's truthful dependency
  reporting); returning `404 SKU_NOT_FOUND` for an unverifiable SKU (explicitly forbidden).

## R13. Per-attempt receipts without new public fields

- **Decision**: Internal receipt identity is `attempt_id`. `receipts(attempt_id PK,
  transaction_id, completed_at)` and `receipt_lines(attempt_id, sku, …)`. The public `Receipt`
  body is built only from the attempt's lines:
  - `transactionId`, `stationId` and `startedAt` come from the transaction;
  - `completedAt` is the attempt's finalization time;
  - `itemCount`, `totalAmount` and `lines` contain only this attempt's purchases.
  No attempt id is exposed. Reconciliation sums `receipt_lines` across distinct attempts.
- **Rationale**: Implements FR-014 and SC-003 and fixes F6's cumulative row.
- **Migration**: New Checkout migrations create the attempt-keyed tables. Existing rows are
  development data and are not migrated (Checkout database reset is part of the cut-over,
  documented in quickstart).

## R14. Baseline and performance method (FR-030, FR-031, SC-009)

- **Decision**:
  1. **Freeze the baseline**: tag `baseline/service-based-v1` at the commit where measurement
     begins (the current `8f40554` plus an *instrumentation-only* commit). That commit adds the
     actuator dependency and metric exposure and changes no business code or configuration
     values. Both deployments record Hikari pool size explicitly (baseline: Spring default 10).
  2. **Stock provisioning**: after migrations, both topologies raise every SKU to 1,000,000 units
     with `initial = physical`, using the Inventory owner's credentials. There are two schema
     variants: `measured-stock-baseline.sql` for the baseline's `available_stock` column, and
     `measured-stock.sql` for the redesign, which also writes a `PROVISION` movement. That covers more
     than 15× the projected top-SKU demand even if redesign throughput doubles
     (≈ 600k scans × 12.2% ≈ 73k). Shortage behavior is tested separately (US1).
  3. **Runs**: per topology, 3 × normal (`--stations=10 --duration=60`) and 3 × stress
     (`--stations=200 --duration=180`). Every run starts from reset databases and broker state,
     follows a 30 s warm-up, and uses the same host. Medians of the three stress runs feed
     SC-009.
  4. **Observations captured per run** by `deployment/scripts/capture-observations.sh` into
     `observations-<timestamp>.json` beside the report:
     - Micrometer timers (`http.server.requests` per route, internal client calls);
     - Hikari (`hikaricp.connections.pending`, `…acquire`, `…usage`);
     - deadlock and lock retries (custom counters, plus MySQL `INNODB_METRICS lock_deadlocks`,
       `lock_row_lock_time`, `lock_row_lock_waits`);
     - top statement latencies from `performance_schema.events_statements_summary_by_digest`;
     - `docker stats` CPU and memory samples every 5 s;
     - outbox backlog and consumer lag.
     Together these separate service time, data access, contention, connection wait, retries
     and resource capacity (FR-030).
  5. **Comparison**: `deployment/reports/COMPARISON.md` tabulates the median scan p95,
     successful-scan throughput, and start/complete p95, with failures included. It lists every
     topology and provisioning difference and states SC-009 as met or unmet.
- **Rationale**: The constitution requires a reproducible, observed baseline before migration,
  and comparable provisioning. F8 shows the existing reports cannot serve.
- **Alternatives considered**: Reusing the 2026-10-16 reports (inadequate stock, single run, no
  observations); per-container CPU caps (would impose an arbitrary split between one and four
  databases).
- **Expected bottleneck hypotheses** (to confirm or reject with the baseline, not to assume):
  - Checkout pool wait with 200 stations and 10 connections;
  - shared-instance contention from Analytics polling and Inventory locks;
  - two queries per scan (`isOpen` plus totals) plus a cross-schema read;
  - deadlock retries.
  Redesign levers, each evaluated against measurements:
  - projection lookup instead of a SQL read;
  - one upsert-plus-totals transaction per scan;
  - a measured pool size;
  - private instances;
  - keep-alive pooled internal HTTP.

## R15. Boundary verification replacing `NoDirectDomainCallsTest`

- **Decision**: Delete `NoDirectDomainCallsTest`; it now forbids the required design. Replace it
  with:
  - `PrivateStoreAccessTest` (integration): for each service account × each other service's
    database instance, attempt connect/select/execute/view access, expecting authentication or
    privilege failure (SC-002, US3-2);
  - `ModuleBoundaryTest` (static):
    - no domain module depends on another domain module;
    - no SQL string names another service's schema;
    - only `service-contracts` types cross modules;
    - the gateway has no `DataSource`, JDBC dependency or saga code (gate 2);
  - provider/consumer contract tests that validate each internal contract's JSON examples on
    both sides (R16).
- **Rationale**: Tests what the constitution now requires instead of what it formerly prohibited.

## R16. Shared code and contract compatibility

- **Decision**: A new `service-contracts` module contains versioned request, response and event
  records only, in packages `…contracts.catalog.v1`, `…contracts.inventory.v1` and
  `…contracts.events.v1`, plus canonical JSON example fixtures. It has no repositories, state or
  business rules.
  - **Compatibility rules**:
    - additive fields only within a version;
    - consumers ignore unknown fields (Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`, the Spring
      Boot default);
    - removing or renaming a field, changing its meaning, or changing a path requires `v2`,
      served alongside `v1` until consumers move.
  - **Enforcement**: Each provider test asserts that its responses still deserialize to the
    `v1` fixtures, and each consumer test parses the fixtures. An incompatible change fails the
    build before deployment (edge case "incompatible internal contract").
- **Rationale**: Allowed by the constitution ("versioned transport contracts"). It keeps services
  independently deployable for compatible changes (FR-027, US3-3).
- **Alternatives considered**: Pact broker (extra infrastructure for four services); duplicating
  DTOs per consumer without fixtures (silent drift).

## R17. Health, readiness and graceful shutdown

- **Decision**: Use the actuator health groups below.

  | Group | Includes | Excludes or detail-only |
  |---|---|---|
  | `liveness` | process state | — |
  | `readiness` | own database plus Flyway migration completion | — |
  | details only, outside readiness | Checkout: Inventory reachability, catalog projection state (`CURRENT`/`STALE`/`EMPTY`), Kafka producer, outbox backlog; Analytics: Kafka consumer, catalog projection state; Inventory: catalog projection state | do not fail readiness |

  A service therefore becomes ready with other domains stopped and reports dependency status
  truthfully (US3-1). Set `server.shutdown=graceful` with a 20 s timeout.
  - Checkout stops accepting requests, lets in-flight completions reach a durable state, and
    stops the relay after its current batch.
  - Analytics stops the listener after the in-flight batch commits (FR-025).
- **Alternatives considered**: Readiness gated on all dependencies (would prevent independent
  start, contradicting US3-1).

## R18. Fault injection for recovery evidence (FR-032)

- **Decision**:
  - A Checkout `FaultInjector` bean, active only with the `fault-injection` profile, halts the
    JVM (`Runtime.halt`) at named saga points: `AFTER_FREEZE`, `AFTER_RESERVE_RESPONSE`,
    `AFTER_PREPARE`, `AFTER_COMMIT_RESPONSE`. The profile is never enabled in measured runs.
  - Toxiproxy (`ghcr.io/shopify/toxiproxy:2.9.0`) between Checkout and Inventory simulates lost
    responses (`limit_data` downstream after the request reaches Inventory) and timeouts.
  - `docker compose stop/kill/pause` simulates Inventory, Kafka and Analytics outages.
  - Integration tests use Testcontainers 1.21.4 (MySQL, Kafka and Toxiproxy modules); 1.20.x cannot talk to Docker Engine 29.
- **Rationale**: Exercises each completion boundary deterministically without production code
  paths that depend on test flags beyond the inert profile.

## R19. Documentation and superseded guidance (FR-033, SC-011)

- **Decision**: `domain-services/deployment/README.md` becomes the single entry point, linking:
  - deployments and ports;
  - an ownership and permission table;
  - internal contracts in this feature's `contracts/`;
  - saga and recovery rules;
  - the analytics flow;
  - baseline and redesign reports with `COMPARISON.md`;
  - reconciliation scripts.
  `ARCHITECTURE.md` is rewritten for the private-store design.
  `architecture-characteristics.md` is also rewritten where it conflicts, rather than annotated.
  It describes the current requirements, not a historical feature, so leaving the old text in
  place would keep a contradicting rule readable as a requirement:
  - its all-or-nothing completion statement becomes partial completion: available units are sold,
    unavailable units stay in the open basket, and receipts are per attempt;
  - its single-MySQL-transaction statement becomes the completion saga of local transactions
    (R8).
  All its other text is preserved.
  `specs/001-self-checkout-api/contracts/error-codes.md` belongs to a historical feature, so it
  keeps its original text and only receives a superseded-by note pointing to
  [public-api-amendment.md](contracts/public-api-amendment.md), which covers scan-time claims and
  `INSUFFICIENT_STOCK` → `CANCELLED`. The existing 2026-10-16
  reports stay in place and are labeled "historical — inadequate stock, not baseline evidence".

## R20. Public contract amendment mechanics (FR-006, FR-034)

- **Decision**: Before any implementation code changes, apply amendments A1–A6 from
  [public-api-amendment.md](contracts/public-api-amendment.md) to
  `spec/self-checkout-openapi.yaml`. They change descriptions and add `409`/`503` response
  entries that reference the existing `ApiError` schema; no schema, path, field or required
  property changes. Bump `info.version` to `1.2.0`. The file currently says `1.0.0`, while
  `specs/001-…/error-codes.md` cites `1.1.0`; the bump resolves the drift. The load client and
  mock server stay unmodified. This is the first implementation task. All six amendments,
  including A6 (R12), are approved.
