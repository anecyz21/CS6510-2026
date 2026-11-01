# Research: Event-Driven Communication Between Checkout Microservices

Phase 0 decisions for [plan.md](plan.md). Each entry gives the decision, the reason, and the
alternatives rejected. Decisions that 004 made and this feature keeps are referenced rather than
repeated ([004 research](../004-domain-services/research.md)).

## R1. Broker, topic ownership and layout

**Decision**: Keep Apache Kafka 3.8.1 (single KRaft node, `auto.create.topics.enable=false`).
Each topic is created by its only publisher through a Spring `NewTopic` bean, and consumers
never create topics.

| Topic | Owner | Consumers | Key | Partitions | Cleanup / retention |
|---|---|---|---|---|---|
| `catalog.products.v1` | Catalog | Checkout, Inventory, Analytics | `sku` | 1 | `compact` (latest per SKU kept forever) |
| `checkout.accepted-scans.v1` | Checkout | Analytics | `transactionId` | 1 | `delete`, 7 days (unchanged from 004) |
| `inventory.commands.v1` | Checkout | Inventory | `attemptId` | 12 | `delete`, 7 days |
| `inventory.replies.v1` | Inventory | Checkout | `attemptId` | 12 | `delete`, 7 days |
| `checkout.dlt.v1` / `inventory.dlt.v1` / `analytics.dlt.v1` | The consuming service | Operators | original key | 1 | `delete`, 14 days |

**Rationale**:
- One partition for products gives every consumer a single offset to store and compare at
  startup. 2,000 SKUs replay in well under a second.
- Keying commands and replies by `attemptId` keeps each attempt's messages in order while
  spreading attempts across partitions.
- 12 partitions leave room for more listener threads than the initial concurrency (R4, R5)
  without repartitioning.
- One dead-letter topic per consuming service keeps the "only one publisher per topic" rule.

**Alternatives considered**:
- RabbitMQ or ActiveMQ: already rejected by the constitution amendment. Kafka is deployed and
  carries scans today.
- A compose `kafka-init` job that creates every topic: couples all services to one deployment
  step and hides ownership.
- Per-source dead-letter topics (`<topic>.dlt`): two consumers of `catalog.products.v1` would
  both publish to one topic.

## R2. Catalog publication: version-based outbox

**Decision**: Catalog has no runtime writes. Item changes arrive only through migrations, which
already bump `catalog_metadata.catalog_version` (004 R6). A new single-row
`catalog_publication.published_version` records the last version fully published.
`ProductPublisher` runs at startup and then every 5 s:

1. If `catalog_version > published_version`, read every row of `catalog_items`, active and
   inactive.
2. Send one `ProductChanged` event per SKU, carrying `catalogVersion`, then flush and wait for
   every acknowledgment.
3. Set `published_version = catalog_version`.

A crash before step 3 republishes the same version, which is harmless (R3). If
`published_version` is set but the topic's end offset is 0 (the broker volume was reset),
Catalog republishes as well. `POST /internal/v1/ops/republish` forces a republish for operators.

**Rationale**: This meets the constitution's outbox rule with an explicitly documented
equivalent. The change and the pending-publication marker sit in the same database, and the
version comparison is the outbox query. No item row is duplicated into a second table.

**Alternatives considered**:
- A per-row `product_outbox` filled by triggers: duplicates data for a table that changes only
  at deploy time.
- Republishing on every startup unconditionally: works, but rewrites 2,000 records per restart
  and makes consumer replays noisy.

## R3. Consumer projections: offset in the same transaction, replay, readiness

**Decision**: Checkout (`catalog_projection`), Inventory (`catalog_names`), and Analytics
(`catalog_names`) keep their 004 tables. `projection_state` gains `topic_offset`, and the 004
`catalog_version` column now holds the highest applied version. Each service has one batch
`@KafkaListener` on `catalog.products.v1` that implements `ConsumerSeekAware`:

- **On assignment**, it reads `topic_offset`. If the offset is null, it seeks to the beginning;
  otherwise it seeks to `topic_offset + 1`. It also records the partition's end offset at that
  moment as the catch-up target.
- **Per batch**, one local transaction upserts each `ProductChanged` (deleting the SKU when
  `active=false`) only if the event's `catalogVersion` ≥ the stored one. It sets `topic_offset`
  to the last record's offset, then swaps the in-memory map.
- **Readiness**: the projection's health indicator is `UP` only when `topic_offset + 1` ≥ the
  catch-up target and the projection is non-empty. Until then the service reports
  `OUT_OF_SERVICE`. Readiness is informational here: the gateway forwards regardless, and Spring
  keeps serving requests while `OUT_OF_SERVICE`. So the request paths enforce it themselves
  (next paragraph).
- **Rebuild**: deleting the projection rows and setting `topic_offset` to null (operator
  endpoint or a restart with an empty database) replays from the beginning.
- The consumer group (`<service>-catalog-projection`) exists only for monitoring. Positions come
  from the database, not from committed group offsets.

The 004 staleness rule (`STALE` after 60 s without a version probe) is removed. Freshness now
means "caught up to the end offset", and lag is reported as a gauge. A scan of a SKU missing from
the projection returns the existing unknown-item error only once the projection has caught up.
While it is still replaying, the miss returns `503 SERVICE_UNAVAILABLE` (004 amendment A6,
"Catalog unverifiable"), and the low-stock and popular-items views do the same when a name is
missing. No fallback lookup is possible or needed.

**Rationale**:
- Storing the offset with the rows makes each projection exactly-once relative to its own
  database, no matter how often the broker redelivers.
- Compaction plus replay from the beginning satisfies "rebuildable from scratch".
- Checking against the end offset captured at startup avoids the empty-or-partial-projection
  window that would wrongly reject valid scans.

**Alternatives considered**:
- Kafka-committed group offsets: the offset commit and the database commit cannot be atomic, so
  a crash can apply or skip a batch twice (skipping is the dangerous case).
- Keeping the 004 `upsertSingle` miss path: it needs a synchronous Catalog lookup, which the
  constitution now forbids.

## R4. Inventory: command listener and reply outbox

**Decision**: `ReservationController` is replaced by `ReservationCommandListener`
(`inventory.commands.v1`, group `inventory-reservations`, concurrency 6, `AckMode.RECORD`).
It dispatches on `messageType`:

- `ReserveRequested` → `ReservationService.reserve`
- `CommitRequested` → `ReservationService.commit`
- `CancelRequested` → `ReservationService.cancel`

`ReservationService` gains a `ReplySink` parameter that it calls inside its existing local
transaction with the resulting `ReservationV1`. The sink inserts one `reply_outbox` row, so the
reservation change and its reply commit together. Each reply is sent after commit by the
`OutboxSender` (R5), and the sweeper catches anything left unsent. The listener's offset is
committed only after the transaction commits. Errors that 004 returned as HTTP 400 or 409
(`INVALID_RESERVATION_REQUEST`, `RESERVATION_MISMATCH`, `RESERVATION_NOT_FOUND`,
`RESERVATION_NOT_COMMITTABLE`, `RESERVATION_COMMITTED`) become `Conflict` replies with the same
codes, so Checkout parks the attempt where operators can see it. Only a record that cannot be
parsed at all skips the reply and goes straight to `inventory.dlt.v1` (R8).

`SaleApplier`, lock ordering, CHECK constraints, and `withLockRetries` are unchanged.

**Rationale**: The reservation logic and its concurrency proofs from 004 stay intact, and only
the entry point changes. Concurrency 6 against a pool of 10 leaves connections for `SaleApplier`
and the sender.

**Alternatives considered**:
- Writing the reply in a second transaction after the reservation commits: correct only because
  of redelivery, and harder to reason about.
- Kafka transactions (consume-process-produce): they cannot include the MySQL write.

## R5. Checkout: reply-driven saga, after-commit send, and waiting within the deadline

**Decision**: `AttemptDriver` and `InventoryClient` are replaced by `CompletionSaga`, which keeps
004's attempt states (`RESERVING`, `COMMITTING`, `CANCELLING`, `FINALIZED`, `REJECTED`,
`CANCELLED`). Each step is one local transaction that changes state and writes the next command
to `command_outbox`:

| Trigger | Local transaction | Command written |
|---|---|---|
| Public completion freezes the basket (004 R10) | Insert attempt `RESERVING` | `ReserveRequested` with the snapshot lines |
| Reply `Reserved` with lines matching the snapshot, attempt in `RESERVING` | → `COMMITTING`, store granted quantities (durable receipt intent) | `CommitRequested` |
| Reply `Released` (nothing available), attempt in `RESERVING` | → `REJECTED`, clear active attempt | none |
| Reply `Cancelled` (tombstone won), attempt in `RESERVING` | → `CANCELLED`, clear active attempt | none |
| Reply `Committed`, attempt in `COMMITTING` | → `FINALIZED`, write receipt lines, update basket (004 `finalizeAttempt`) | none |
| Reply `Released`/`Cancelled`, attempt in `CANCELLING` | → `CANCELLED`, clear active attempt | none |
| Recovery: attempt in `RESERVING` longer than `cancel-after` (30 s) | → `CANCELLING` | `CancelRequested` |

Any other combination is a stale or duplicate reply. It is ignored and counted
(`checkout.saga.reply.ignored{reason}`). A combination that should be impossible, such as a
`Conflict` reply or lines that don't match the snapshot, parks the attempt with
`needs_attention`, as in 004.

**Sending**:
- `OutboxSender` registers a `TransactionSynchronization.afterCommit` that hands the new rows to
  a sender thread. The thread sends with `KafkaTemplate`, then marks acknowledged rows with
  `published_at` in one batched update.
- A sweeper runs every 1 s and resends rows still unsent after 2 s. It is safe because every
  receiver is idempotent (R6).
- **Per-attempt order (FR-010)**: the sweeper and the after-commit path share one sender thread
  per service. Before sending a newly committed row, that thread first sends any older unsent rows
  for the same `attemptId`, in `outbox_id` order. Without this, after an outage a new
  `CancelRequested` could overtake a still-unsent `ReserveRequested`. That would be harmless,
  because a cancel that arrives first leaves a tombstone that makes the later reserve hold
  nothing, but it would break FR-010 as written.
- Producer settings are `acks=all`, `enable.idempotence=true`, `linger.ms=0`, and
  `max.in.flight.requests.per.connection=5`, so ordering per key is preserved.

**Waiting**:
- `AttemptWaiters` maps `attemptId` to a `CompletableFuture<Outcome>`. The public request and
  any joined requests (004 A5) wait on it.
- The reply listener completes it in `afterCommit` of a terminal transition.
- As a fallback, the waiter reads the attempt from the database every 250 ms. This covers a
  reply applied by another Checkout replica, and a terminal state reached before the waiter
  registered.
- At the 5 s deadline the request returns `503 SERVICE_UNAVAILABLE`, and the attempt keeps
  settling in the background (FR-012).

**Reply listener**: `inventory.replies.v1`, group `checkout-saga`, concurrency 6,
`AckMode.RECORD`.

**Latency budget**: four broker hops (two commands, two replies), each about 2–5 ms on a local
single-node broker with `linger.ms=0`, plus four short local transactions. The target is
complete p95 ≤ 100 ms at 100 stations. The 004 HTTP saga's measured stress complete p95 was
about 1.95 s at 200 stations, so the target is plausible but is reported as measured either way.

**Alternatives considered**:
- `ReplyingKafkaTemplate` request-reply: blocks a thread per call, keeps the "remote call inside
  a step" shape, and has no outbox.
- Leaving the 004 lease-based driver in place and swapping only the client: the driver's
  synchronous loop would wait on each reply while holding a lease, so the transport changes but
  the design stays the same.
- The 200 ms polling relay used for scans: adds up to 800 ms per completion (see the plan's
  Complexity Tracking table).
- A `GetStatus` command: the three commands are idempotent, so resending the current one already
  returns the stored outcome. Leaving it out saves a message type.

## R6. Idempotency per receiver

**Decision**: Each receiver relies on a natural idempotency key that commits with its effect,
instead of a generic inbox table:

| Receiver | Key | Why a repeat has no effect |
|---|---|---|
| Inventory commands | `attempt_id` primary key on `reservations` (004) | A repeated reserve, commit, or cancel returns the stored state and applies no second stock change. Each repeat writes another reply, which Checkout ignores |
| Checkout replies | Conditional `UPDATE … WHERE attempt_id = ? AND state = ?` | Only the first reply in the expected state moves the attempt. Later ones match zero rows |
| Projections | `topic_offset` in `projection_state` plus the `catalogVersion` check | Records at or below the stored offset are skipped |
| Analytics scans | `ingested_scans.event_id` unique key (004 R5) | Unchanged |

**Rationale**: The constitution requires deduplication changes to commit atomically with the
business write. In every case above the deduplication record is the business row itself.

**Alternatives considered**: A `processed_messages(message_id)` inbox per service. Resends carry
new message IDs, so an inbox keyed on message ID would not even catch recovery resends. It adds
a write per message for no extra guarantee.

## R7. Message envelope and schema evolution

**Decision**: Every record carries these Kafka headers:
- `messageId` (UUIDv7)
- `messageType`
- `schemaVersion`
- `producer` (`catalog`, `checkout`, or `inventory`)
- `occurredAt` (ISO-8601 UTC)
- `correlationId` (the `attemptId`, on commands and replies)
- `causationId` (on a reply, the `messageId` of the command it answers)

The JSON body repeats `messageId`, `messageType`, and `schemaVersion`, matching
`AcceptedScanV1`, so a body read from a dead-letter topic is self-describing. Consumers ignore
unknown fields (`FAIL_ON_UNKNOWN_PROPERTIES=false`). A higher `schemaVersion` with the same major
topic version is processed using the known fields. A breaking change gets a new topic (`…v2`).
`AcceptedScanV1` gains the `producer` and `occurredAt` headers additively and its body is
unchanged.

**Rationale**: Headers let dead-letter tooling and metrics read the envelope without parsing
bodies. Version in the topic name is the 004 convention.

**Alternatives considered**: CloudEvents or Avro with a schema registry. Either one adds a
registry container and libraries for four schemas that the JSON fixtures already pin.

## R8. Retries and dead-letter topics

**Decision**: Each consumer uses `DefaultErrorHandler` with an exponential backoff of 200 ms
doubling to 5 s, for 4 attempts, followed by `DeadLetterPublishingRecoverer` to the service's own
dead-letter topic. The recoverer adds the original topic, partition, offset, and exception as
headers. Deserialization failures and validation errors are classified not-retryable and go to
the dead-letter topic immediately. Errors that can heal, such as database lock timeouts or a
lost connection, are retried, and the listener pauses rather than skipping while the database
is down. Health detail and the `messaging.dlt.published` counter show dead-letter counts.
`reconcile.sh` reports them.

**Rationale**: A poison message stops blocking its partition after about 7 s, and nothing is
dropped (FR-017).

**Alternatives considered**: Retry topics with delayed redelivery (`@RetryableTopic`). These lose
per-attempt ordering, which commands need.

## R9. Readiness, health, shutdown, deployment, and the gateway

**Decision**:
- **Readiness** in every domain service covers `readinessState`, `db`, `kafka` (an AdminClient
  `describeCluster` with a 2 s timeout, reusing Checkout's `KafkaHealthIndicator`), and
  `catalogProjection` where the service has one.
- `InventoryHealthIndicator` is deleted, and so are `catalog.url` and `inventory.url` in
  `application.yml` and `CATALOG_URL` and `INVENTORY_URL` in compose.
- **Graceful shutdown**: listener containers stop first, so in-flight records finish and
  offsets commit. Then outbox senders drain their queues, with a 10 s cap, and anything left
  is picked up by the sweeper on the next start.
- The gateway is unchanged. Its four `SERVICES_*_URL` values are the only service addresses in
  the deployment.

**Rationale**: Readiness must be truthful about the broker, because a service without it cannot
send outbox rows or receive replies. Nothing acts on readiness while services are running: it
only orders startup (`depends_on … service_healthy`) and feeds monitoring. So a broker outage
marks every service not ready while scans keep succeeding, as the spec's broker edge case
requires. When Inventory is down, Checkout itself stays ready, since
scans still work, and completions return 503 by the request deadline.

## R10. Boundary enforcement (FR-022)

**Decision**: Add `NoInterServiceHttpTest` to `integration-tests/boundaries/`. It uses the same
source-scanning approach as 004's `ModuleBoundaryTest`, so no new test library is needed. It
fails when any of these holds:
1. A domain service's `src/main/java` sources import or reference `java.net.http.HttpClient`,
   `org.springframework.web.client.RestClient`, `RestTemplate`, or `WebClient`.
2. A domain service's `application.yml` contains a key ending in `.url` other than its datasource
   URL.
3. A domain service's compose environment has a value matching `http://(catalog|checkout|
   inventory|analytics)[:/]`.

The gateway module is excluded explicitly.

**Rationale**: It makes the assignment's "all communication uses events" requirement fail the
build, not just appear in review.

## R11. Measurement, reports, and comparison

**Decision**:
- **Script changes**: `run-measurement.sh` gains two profiles. `default` passes no workload flags,
  so the client uses its built-in 10 stations for 60 s, exactly `java -cp out Main`. `stress100`
  passes exactly `--stations=100 --duration=120`. The script also gains the topologies
  `pre-event-driven` and `event-driven`. The 004 `normal`/`stress` (200 × 180) profiles remain
  for history.
- **004 comparison**: at tag `pre-event-driven`, run both new profiles.
- **005 runs**: repeat both profiles on the same host, after `reset.sh` and measured-stock
  provisioning.
- **Repeats**: three runs per profile and build. The JSON report from each run is committed
  under `deployment/reports/event-driven/{baseline-004,default,stress}/` with its observations
  and reconciliation files.
- **Graded reports**: `NOTES.md` names the median-scan-p95 run of each 005 profile as the
  graded report. It also records topology, partitions, listener concurrency, producer
  settings, pool sizes, stock seed, hardware, and the explanation of any errors.
- **Comparison**: `compare-reports.sh` gains the two new profiles and writes
  `reports/event-driven/COMPARISON.md`.
- **New observations**: `capture-observations.sh` adds consumer lag per group, outbox pending
  counts, dead-letter counts, and the `checkout.saga.roundtrip` timer (time from command
  written to terminal reply applied).

**Rationale**: The graded stress profile (100 stations, 120 s) differs from 004's measured
profile (200 stations, 180 s). Comparing against 004's existing reports would mix workload and
architecture effects, so 004 is re-measured with the same profiles.

**Alternatives considered**: A single run per profile. It meets the assignment, but there would
be no spread to show whether a difference is noise.

## R12. Architecture diagram

**Decision**: `deployment/ARCHITECTURE.md` is rewritten around a Mermaid `flowchart LR`. It shows:
- the load client and the gateway, with the gateway's HTTP edges to the four services
- each service with its own MySQL database
- Kafka as a box containing the four business topics and three dead-letter topics
- an arrow for every publish and every consume, labeled with the topic and message types

A rendered `deployment/architecture.svg` is committed beside it.
`scripts/render-diagram.sh` produces the SVG with a pinned `@mermaid-js/mermaid-cli` version run
through Docker. The root `README.md` links the SVG, `ARCHITECTURE.md`, and the two graded
reports.

**Rationale**: GitHub renders Mermaid inline. The SVG covers reviewers who open the file outside
GitHub, and it is the "architecture diagram" artifact the assignment asks for.

**Alternatives considered**: A hand-drawn image only, which can't be diffed or kept in sync, or
PlantUML, which GitHub doesn't render.

## R13. Fault injection for messaging evidence (FR-028)

**Decision**: Reuse 004's `faults` Spring profile and compose override, adding the following:
- `messaging.faults.duplicate-sends=true`: every `OutboxSender` and the scan relay send each
  record twice, which covers duplicate delivery of all four message types.
- **Poison message**: a test publishes a malformed record to `inventory.commands.v1` and to
  `inventory.replies.v1`, then checks that it lands in the dead-letter topic and that later
  records still flow.
- **Broker restart**: `docker compose restart kafka` during a stress run.
- **Service outages**: `docker compose stop catalog|analytics|inventory` for 60 s during a run.
  The Inventory outage is kept under 25 s for the late-reply case, below `cancel-after`.
- **Checkout crash points**: `HaltingFaultInjector` keeps its checkpoints, renamed to
  `AFTER_FREEZE`, `AFTER_RESERVED_APPLIED`, and `AFTER_COMMITTED_APPLIED`.

Toxiproxy between Checkout and Inventory is removed, because they no longer have a direct
connection.

**Rationale**: Each spec scenario (US2, US3) needs a repeatable trigger. The duplicate-send
switch exercises every receiver's idempotency with one setting.
