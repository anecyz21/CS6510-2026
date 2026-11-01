# Implementation Plan: Event-Driven Communication Between Checkout Microservices

**Branch**: `005-event-driven-services` (feature folder; working Git branch `event-based`) |
**Date**: 2026-10-31 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/005-event-driven-services/spec.md`

**Governance**: [Constitution v4.0.0](../../.specify/memory/constitution.md)

## Summary

Replace every HTTP call between Catalog, Checkout, Inventory, and Analytics with Kafka messages.
The 004 design already has the right shape: private stores, a transactional outbox, idempotent
commands keyed by the attempt ID, and catalog projections. What changes is the transport and who
drives each step.

- **Catalog data becomes an event stream.** Catalog publishes every product to the compacted
  topic `catalog.products.v1`, from a state-based outbox: each item is published whenever the
  catalog version moves past the last version published. Checkout, Inventory, and Analytics
  build their existing projection tables from that topic. Each stores its read position in the
  same transaction as the rows it applies, and none reports ready until it has caught up.
  The internal Catalog HTTP API and all three `CatalogClient`s are deleted.
- **Completion becomes a reply-driven saga.** Each attempt state transition writes the next
  command (`Reserve`, `Commit`, or `Cancel`) to a Checkout command outbox in the
  same local transaction. The command is published to `inventory.commands.v1` right after
  commit, and a sweeper republishes anything left unsent. Inventory runs the existing idempotent
  `ReservationService` and answers on `inventory.replies.v1` through its own outbox. Checkout's
  reply consumer applies each reply as a conditional state transition, and the request thread
  waits on an in-process signal for the attempt to settle, up to the existing 5-second deadline.
  `HttpInventoryClient`, the Inventory reservation HTTP API, and `InventoryHealthIndicator` are
  deleted.
- **Accepted scans are unchanged** (`checkout.accepted-scans.v1`), apart from additive headers.
- **Failures are visible.** A message that keeps failing to process goes to the consuming
  service's dead-letter topic, and an automated boundary test fails the build if a domain
  service regains a client for, or the address of, another domain service.
- **Evidence.** The 004 commit is measured with the two graded profiles: the default run and
  `--stations=100 --duration=120`. The event-driven build is measured the same way. The JSON
  reports, notes, and comparison are committed with an architecture diagram linked from the
  repository README.

Decisions and rejected alternatives are in [research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies** (unchanged from 004):
- Spring Boot 3.4.3 (Spring MVC, Spring JDBC, actuator/Micrometer)
- Spring for Apache Kafka (Boot-managed 3.3.x): `KafkaTemplate`, `@KafkaListener`,
  `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, `KafkaAdmin` `NewTopic` beans
- Flyway 10.22.0 (MySQL) and MySQL Connector/J
- No new runtime libraries. All JDK `HttpClient` and `RestClient` use is removed from domain
  services; only the gateway keeps HTTP.

**Storage**:
- The four private MySQL 8.4 instances from 004, with new migrations per owner:
  - Catalog: publication progress.
  - Checkout: command outbox, attempt send tracking, and the projection read position.
  - Inventory: reply outbox and the projection read position.
  - Analytics: the projection read position.
- Apache Kafka 3.8.1 (single KRaft node), with topics created by their publishing service:
  `catalog.products.v1`, `checkout.accepted-scans.v1`, `inventory.commands.v1`,
  `inventory.replies.v1`, and one dead-letter topic per consumer (`checkout.dlt.v1`,
  `inventory.dlt.v1`, `analytics.dlt.v1`).

**Testing**:
- JUnit 5 and Spring Boot Test for unit and slice tests.
- Testcontainers 1.21.4 (MySQL, Kafka) for consumer, producer, and outbox integration tests.
- Docker Compose system tests for the end-to-end, outage, duplicate-delivery, and
  broker-restart scenarios.
- Message-contract fixture tests in `service-contracts` (provider and consumer validate the
  same JSON fixtures).
- A boundary test that fails on inter-service HTTP clients or addresses (R10).
- The unchanged load client for the default and stress runs, and `reconcile.sh` afterwards.

**Target Platform**: Developer workstation or Linux host with JDK 21, Maven 3.9, and Docker with
Compose v2. The 004 comparison runs and the 005 runs use the same host.

**Project Type**: Multi-module web-service system: four domain microservices plus a
transport-only API gateway, each packaged as an executable JAR and container image.

**Performance Goals** (spec SC-002, SC-003, SC-011):
- The default run (10 stations, 60 s) completes with zero operation errors.
- The stress run (100 stations, 120 s) has at least 99% successful operations, and every
  failure is categorized.
- Completion p95 in the stress run stays well below the 5-second request deadline. The design
  budget is 100 ms (R5), and the run reports the measured value.
- Scan p95 is not expected to change, because the scan path already uses the local projection.
- No improvement target. Deltas against the 004 runs of the same profiles are reported as measured.

**Constraints**:
- The public OpenAPI behavior from 004 (amendments A1–A7) is unchanged.
- The load client and mock server stay unmodified.
- Domain services hold only their own database and broker addresses (FR-002).
- No local transaction or row lock is held while waiting for a reply.
- The completion deadline stays at 5 s, below the client's 10 s.
- Analytics stays off the synchronous path.
- Prices remain exact cents.

**Scale/Scope**:
- 5 deployables, 4 private databases, and 4 business topics plus 3 dead-letter topics.
- 4 message contracts: product event, inventory command, inventory reply, accepted scan.
- 2,000-SKU catalog and baskets of 1–20 units.
- About 2 command and 2 reply messages per completion, and one accepted-scan event per scan.

## Constitution Check

*GATE: Evaluated before Phase 0 research. Re-checked after Phase 1 design (last column).*

| Constitution requirement | Design response | Pre | Post |
|---|---|---|---|
| **I.** Public contract authoritative; completion reports only confirmed outcomes | The seven routes and bodies are unchanged. A 200 is sent only after the reply-driven transition to `FINALIZED` commits. An unsettled attempt at the deadline returns `503` as in 004 (R5). | Pass | Pass |
| **II.** Harness unchanged; runs record version, topology, broker and data settings | Load client used only through flags. Each report pairs with a notes file recording commit, topology, partitions, producer and consumer settings, stock seed, and timeouts (R11). | Pass | Pass |
| **III.** Four separately deployable services with private stores | Unchanged from 004. New migrations touch only their owner's database. | Pass | Pass |
| **III.** All communication between domain services goes through the broker; no domain-service addresses in config; no relaying through the gateway | `CATALOG_URL` and `INVENTORY_URL` are removed from three services. The `CatalogClient`s, `HttpInventoryClient`, and the internal Catalog and reservation HTTP APIs are deleted. Catalog data arrives through `catalog.products.v1` and completion through `inventory.commands.v1` and `inventory.replies.v1`. `NoInterServiceHttpTest` enforces this (R2–R5, R10). | Pass | Pass |
| **III.** Gateway is edge routing only | The gateway is unchanged: single-owner pass-through, no aggregation, no datasource. Its four upstream URLs are the only service addresses in the deployment (R9). | Pass | Pass |
| **III.** Catalog projections are rebuildable by replay, record their position, and gate readiness | The projection rows and topic offset are written in one transaction. Startup seeks to the stored offset, or to the beginning when there is none. Readiness requires the projection to have reached the end offsets captured at startup and to be non-empty. Deleting the projection rows forces a full replay (R2, R3). | Pass | Pass |
| **IV.** Reservations at completion; `stock ≥ reserved ≥ 0`; only commit decrements | `ReservationService`, `SaleApplier`, and their constraints are reused unchanged behind a message listener (R4). | Pass | Pass |
| **IV.** Saga through broker commands and replies; correlation by attempt ID; durable intent before commit; idempotency; a missing reply is an unknown outcome; restart recovery; visible failures | Each transition writes the next command to the outbox in the same transaction. Replies apply as conditional transitions keyed by attempt ID and expected state. `COMMITTING` is durable before `Commit` is sent. The recovery sweep resends the same idempotent command with backoff. `CANCELLING` is entered only from `RESERVING` after `cancel-after`. `needs_attention` and the operator endpoint are kept (R5, R6, [data-model.md](data-model.md)). | Pass | Pass |
| **IV.** Request may wait within a bound; non-success after it while the saga continues | The request waits on an in-process future completed by the reply consumer after its commit, with a database check as fallback, until the 5 s deadline. It then returns `503`, and the attempt keeps settling (R5). | Pass | Pass |
| **IV.** Reconciliation after normal, stress, and failure runs | `reconcile.sh` from 004, extended to report unsent outbox rows and dead-letter counts (R11). | Pass | Pass |
| **V.** Default and stress JSON reports plus architecture diagram, committed and reachable | `deployment/reports/event-driven/` holds both reports, notes, and the 004 comparison. `deployment/ARCHITECTURE.md` holds the Mermaid diagram, with a rendered SVG committed beside it. The root `README.md` links all three (R11, R12). | Pass | Pass |
| **V.** Measure broker latency and consumer lag; no speed claims from architecture alone | Timers for command publish to reply applied, and consumer-lag gauges per group. The comparison declares differences (R11). | Pass | Pass |
| **Constraints:** Kafka designated; topics document owner, consumers, key, retention, schemas; compacted product topic | Topic catalog in [contracts/README.md](contracts/README.md). Owners create their topics with `NewTopic` beans. `catalog.products.v1` uses `cleanup.policy=compact` (R1, R2). | Pass | Pass |
| **Constraints:** Envelope fields; ordering keys; bounded retries then dead-letter | Standard headers on every message (R7). Ordering keys: SKU for products, attempt ID for commands and replies, one partition for scans. `DefaultErrorHandler` with backoff, then `DeadLetterPublishingRecoverer` to the consumer's dead-letter topic (R8). | Pass | Pass |
| **Constraints:** Outbox in the same local transaction; idempotent receivers; offsets after durable processing; no starvation; no lock held while waiting for a reply | Command and reply outboxes are written in the business transaction. Catalog uses a version-based outbox equivalent (R2). Receivers are idempotent through conditional transitions, keyed reservations, and offset-in-transaction projections (R6). Listener offsets are committed after the local transaction (`AckMode.RECORD`). Sweepers select only unsent rows. | Pass | Pass |
| **Constraints:** Readiness includes broker connectivity; graceful shutdown | Readiness covers own database, broker, and projection catch-up. Shutdown stops listeners, then drains outbox senders (R9). | Pass | Pass |
| **Quality gates 1–7** | Gate 1 → Q1. Gate 2 → Q2 and `NoInterServiceHttpTest`. Gate 3 → Q3. Gate 4 → Q9 and Q10. Gate 5 → Q5 and Q6. Gate 6 → Q4, Q7, and Q8. Gate 7 → Q11 ([quickstart.md](quickstart.md)). | Pass | Pass |

**Gate result**: PASS. No violations need justification.

**Pre-implementation blocker (not a constitution issue)**: the working copy has two uncommitted
edits that break compilation:
- `checkout-service/.../CheckoutRepository.java`: the `OpenState` record's `activeAttemptId`
  field is split into `String\n  mptId`.
- `inventory-service/.../SaleApplier.java`: a stray `=` after the `transactions` field.

Both look accidental. Task 1 must restore or fix them before any build, but only after the user
confirms. This plan does not change them.

## Project Structure

### Documentation (this feature)

```text
specs/005-event-driven-services/
├── spec.md
├── plan.md                              # this file
├── research.md                          # Phase 0 decisions R1–R13
├── data-model.md                        # Phase 1 tables, state machine, message flows
├── quickstart.md                        # Phase 1 validation scenarios Q0–Q11
├── contracts/
│   ├── README.md                        # topic catalog, envelope, dead-letter, what stays HTTP
│   ├── catalog-product-event.md         # catalog.products.v1
│   ├── inventory-reservation-messages.md# inventory.commands.v1 + inventory.replies.v1
│   └── accepted-scan-event.md           # checkout.accepted-scans.v1 (additive header change)
├── checklists/requirements.md
└── tasks.md                             # Phase 2 ($speckit-tasks, not created here)
```

### Source Code (repository root)

```text
README.md                                 # + "Event-driven submission" links (reports, diagram)
domain-services/
├── service-contracts/
│   └── src/main/java/edu/northeastern/cs6510/contracts/
│       ├── messaging/                    # NEW: MessageHeaders constants, Envelope helper (no domain logic)
│       ├── catalog/v1/                   # ProductV1 kept; ProductEventV1 NEW; CatalogVersionV1/ProductPageV1 removed
│       ├── inventory/v1/                 # ReserveRequestV1 etc. kept as payloads; InventoryCommandV1, InventoryReplyV1 NEW
│       └── events/v1/                    # AcceptedScanV1 unchanged
├── catalog-service/
│   ├── .../catalog/internal/             # DELETED (CatalogInternalController)
│   ├── .../catalog/publish/              # NEW: ProductPublisher (version-based outbox), topic bean
│   └── src/main/resources/db/migration/  # V4: catalog_publication
├── checkout-service/
│   ├── .../checkout/catalog/             # CatalogClient, CatalogProjectionLoader DELETED; ProductEventListener NEW
│   ├── .../checkout/inventory/           # DELETED (InventoryClient, HttpInventoryClient)
│   ├── .../checkout/completion/          # AttemptDriver → CompletionSaga (reply handler + transitions), AttemptWaiters NEW
│   ├── .../checkout/messaging/           # NEW: CommandOutbox, OutboxSender (after-commit send + sweeper), ReplyListener
│   ├── .../checkout/recovery/            # RecoveryWorker → resend/cancel sweep (no remote calls)
│   ├── .../checkout/health/              # InventoryHealthIndicator DELETED; CatalogProjection readiness = caught up
│   └── src/main/resources/db/migration/  # V7: command_outbox, attempt send columns, projection offset
├── inventory-service/
│   ├── .../inventory/catalog/            # CatalogClient, loader DELETED; ProductEventListener NEW
│   ├── .../inventory/reservation/        # ReservationController DELETED; CommandListener NEW; ReservationService reused
│   ├── .../inventory/messaging/          # NEW: ReplyOutbox, OutboxSender
│   └── src/main/resources/db/migration/  # V9: reply_outbox, projection offset
├── analytics-service/
│   ├── .../analytics/catalog/            # CatalogClient, loader DELETED; ProductEventListener NEW
│   └── src/main/resources/db/migration/  # V5: projection offset
├── api-gateway/                          # unchanged
├── deployment/
│   ├── docker-compose.yml                # CATALOG_URL / INVENTORY_URL removed from domain services
│   ├── ARCHITECTURE.md                   # rewritten: Mermaid diagram + topic flows
│   ├── architecture.svg                  # NEW: rendered diagram
│   ├── scripts/                          # reconcile.sh + outbox/DLT counts; run-measurement.sh profiles
│   └── reports/event-driven/             # NEW: default/, stress/, baseline-004/, NOTES.md, COMPARISON.md
└── integration-tests/
    └── src/test/java/edu/northeastern/cs6510/
        ├── boundaries/   # NoInterServiceHttpTest NEW (+ existing private-store tests)
        ├── messaging/    # duplicate delivery, broker restart, dead-letter, projection replay
        ├── completion/   # existing scenarios re-pointed at the message saga; late reply after 503
        └── deployment/   # per-service outage (Catalog / Analytics / Inventory down)
```

**Structure Decision**: Keep the 004 Maven reactor and module set. No module is added. Each
service gains a `messaging/` package that holds its outbox sender and listeners, so the business
packages (`scan/`, `completion/`, `reservation/`, `ingest/`) stay transport-free. `ReservationService`
and `SaleApplier` keep their logic and are called from a listener instead of a controller.
`service-contracts` gains only message records, header constants, and fixtures.

## Phase Outline (for `$speckit-tasks`)

Tasks must keep this order:

1. **Unblock the build.** With the user's confirmation, fix the two broken working-copy edits.
   Then tag the current commit `pre-event-driven` as the 004 comparison point.
2. **Measure 004 first.** At the tag, run the default profile and the
   `--stations=100 --duration=120` profile, and record reports, observations, and
   reconciliation under `reports/event-driven/baseline-004/` (R11).
3. **Shared pieces.** Add message records, headers, and fixtures to `service-contracts`, plus a
   generic outbox sender pattern in each service. Topics are created by their owners.
4. **Catalog stream.** Build `ProductPublisher` in Catalog, then the projection listeners in
   Checkout, Inventory, and Analytics, then readiness gating. Delete the three `CatalogClient`s
   and the internal Catalog API only after all three listeners pass.
5. **Inventory side.** Build the command listener and reply outbox in Inventory. Delete the
   reservation HTTP controller last.
6. **Checkout side.** Build the command outbox, reply listener, `CompletionSaga`, waiters, and
   the recovery resend sweep. Then delete `HttpInventoryClient`, `InventoryHealthIndicator`, and
   the URL settings.
7. **Dead-letter handling** in all three consumers, plus the fault-profile duplicate-send switch.
8. **Tests.** Boundary test, message-contract fixtures, the completion suite re-pointed at the
   message saga, outage, duplicate, broker-restart, and replay tests, then reconciliation.
9. **Measure 005.** Run both profiles, then write `NOTES.md` and `COMPARISON.md`.
10. **Documentation.** `ARCHITECTURE.md` diagram, SVG export, topic catalog, and the root README
    links.

## Complexity Tracking

No constitution violations. Two choices add moving parts and are recorded for review:

| Item | Why needed | Simpler alternative rejected because |
|---|---|---|
| After-commit send plus sweeper for command and reply outboxes (instead of the 200 ms polling relay used for scans) | Completion makes four broker hops on the request path. A 200 ms poll on each outbox would add up to about 800 ms per completion | Publishing straight to Kafka without an outbox breaks the constitution's atomic state-and-message rule. Kafka transactions cannot span MySQL |
| In-process waiter registry with a database fallback | The request thread must learn about a reply that a listener thread applied, without polling the database every few milliseconds | Database polling alone, at 50 ms as 004's `awaitOtherDriver` does, adds 25 ms on average to every completion and puts read load on Checkout's database under stress |
