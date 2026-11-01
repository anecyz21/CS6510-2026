# Self-Checkout Semester Project Constitution

## Core Principles

### I. API Contract is Authoritative

The provided OpenAPI contract defines the externally visible behavior of the system.
Implementations MUST preserve the documented endpoints, request/response formats, status
codes, and synchronous client-facing behavior. Internal deployment boundaries and internal
transports MAY change without changing the public contract. A completion response MUST report
only confirmed fulfillment; accepting a workflow or publishing a command is not a successful
completion. Timeout and retry behavior MUST be documented within the existing public contract.
Introducing a pending response or asynchronous public completion API requires an explicit
contract amendment.

Rationale: A stable public contract allows different architectures to be measured by the
same client and compared fairly, without hiding unfinished work behind faster responses.

### II. Test Harness Consistency

The load client and mock server are measuring instruments, not service implementation code.
Implementations MUST use them unmodified; only documented client flags MAY vary per run.
Every measured run MUST record the service version, deployment topology, broker settings,
database settings that affect data volume, and workload parameters.

Rationale: A consistent instrument and recorded environment make throughput and latency
measurements interpretable across machines and architectural revisions.

### III. Independently Deployable Microservices, Private Data & Event-Only Communication

The system MUST consist of separately buildable, runnable, and deployable microservices:
Catalog, Checkout, Inventory, and Analytics. Each service MUST own a private data store,
credentials, and migrations. Other services MUST NOT access that store through SQL queries,
cross-schema joins, foreign keys, views, stored procedures, or another service's outbox.
Shared identifiers such as SKU, transaction ID, and completion-attempt ID link records
logically; they MUST NOT create cross-database integrity constraints.

All communication between Catalog, Checkout, Inventory, and Analytics MUST be messages carried
by the designated event broker: domain events, commands, and reply events. A service MUST NOT
call another domain service over HTTP, gRPC, raw sockets, or any other direct channel, and its
configuration MUST NOT contain another domain service's network address. A service MUST NOT
use the gateway as a relay to reach another service.

An API gateway MUST preserve the single public HTTP surface. It MAY forward each public request
to the one service that owns it over HTTP; that ingress hop is the only permitted synchronous
internal call. The gateway MUST contain only routing, contract translation, authentication if
introduced, and transport-level concerns. It MUST NOT own domain data, coordinate business
workflows, or aggregate responses from several services.

Each microservice MUST have a single documented business responsibility:

- Catalog owns item definitions, SKU validity, and authoritative prices, and publishes product
  change events.
- Checkout owns transaction lifecycle, baskets, captured purchase prices, receipts, and durable
  completion workflow state. Checkout MUST orchestrate completion by exchanging commands and
  reply events with Inventory.
- Inventory owns stock, reservations, fulfillment decisions, and inventory movement records,
  and acts only on commands received from the broker.
- Analytics owns accepted-scan consumption, hopping windows, ranking, and published snapshots.

Services that need Catalog data (Checkout for SKU validation and prices, Analytics for item
names) MUST maintain a local projection built from Catalog events. The projection MUST be
rebuildable by replaying the broker topic from the beginning, MUST record the last applied
event position, and MUST have a documented freshness and versioning policy. A service MUST NOT
report ready until its projection has caught up to the topic position observed at startup.
Checkout MUST retain the purchase-price snapshot used for each basket or receipt line.

Analytics MUST consume accepted-scan events without synchronous participation in checkout.
Its named internal pipeline MUST remain `ingest -> window -> rank-and-publish`, with one
responsibility per filter and only adjacent internal pipes. Shutdown MUST drain accepted work
or leave it durably recoverable for restart.

Rationale: Private stores and broker-only messaging decouple services in time as well as in
data: a consumer that is down or slow delays work rather than failing its producer, and every
interaction is recorded where it can be replayed, audited, and measured.

### IV. Inventory Correctness, Partial Completion & Durable Coordination

Scanning MUST NOT decrement stock or reject a valid scan solely because stock is unavailable.
Inventory reservations MUST be established during completion, with local atomic transactions
or conditional updates enforcing `stock >= reserved >= 0`. Reservations reduce availability;
only committing fulfillment decrements physical stock. For every SKU, stock MUST never be
negative, and the stock decrease after all in-flight completion attempts settle MUST equal
completed receipt-line quantities. During recovery, every committed stock movement MUST map
to a durable completion attempt that can finish recording its receipt.

When one or more scanned items are unavailable at completion, the system MUST fulfill the
available quantity, MUST NOT decrement stock for unavailable quantities, MUST retain those
quantities in the basket for a later attempt, and MUST identify them in the customer-facing
result. Unavailable items MUST NOT block fulfillment of otherwise available items.

An API request that changes multiple service databases MUST use a documented durable workflow
of local transactions connected by broker messages, not a shared database transaction or a
synchronous remote call. Checkout's completion saga MUST:

- Freeze or version the basket for the attempt so concurrent scans and completions cannot
  change the fulfillment request while it is being processed.
- Persist workflow progress and use a stable, unique completion-attempt ID distinct from the
  transaction ID, because a partially fulfilled transaction can have later completion attempts.
  Every saga command and reply MUST carry that ID as its correlation ID.
- Publish a reserve command, durably record the fulfillment and receipt intent on Inventory's
  reserved reply, publish a commit command, and finalize Checkout's receipt and basket on
  Inventory's committed reply.
- Make commands, replies, and redeliveries idempotent, preventing duplicate decrements and
  receipt lines.
- Treat a missing reply as an unknown outcome; re-send the same idempotent command or a status
  query before deciding whether to compensate. Before inventory commit, a cancelled attempt
  MUST publish a release command. After inventory commit, recovery MUST finish the corresponding
  Checkout record; a missing reply alone MUST NOT trigger stock restoration or a second sale.
- Define restart recovery and bounded retry/backoff policies, surface unresolved attempts for
  recovery, and release abandoned reservations through a durable cancellation/recovery policy.
  Reservation expiry, if introduced, MUST be coordinated with commit to prevent contradictory
  outcomes. Permanent failures MUST remain visible rather than silently losing work.

The public completion request MAY wait for the saga's reply events within a documented bound.
If the outcome is not confirmed within that bound, the response MUST follow the contract's
documented non-success behavior while the saga continues durably in the background.

No single local transaction makes the entire saga atomic across databases. Reconciliation MUST
verify both the settled inventory invariant and the absence of unexplained in-flight attempts
or abandoned reservations after normal, stress, and injected-failure workloads.

Rationale: Local stock enforcement prevents overselling; durable messaging and idempotency
preserve correct outcomes despite crashes, redelivery, and replies that arrive late or not at all.

### V. Evidence-Based Performance & Microservice Artifacts

Every submission MUST commit, with the code and reachable from the submitted repository URL:

- A timestamped JSON report from the unchanged load client run with default parameters.
- A timestamped JSON report from the stress run `java -cp out Main --stations=100 --duration=120`.
- An architecture diagram showing the gateway, each microservice, each private database, the
  event broker, and the topics or message flows between them with their direction.

Analysis MUST report mean, p95, and p99 latency, throughput, errors, workload configuration,
and deployment topology. Non-zero errors MUST be explained. Comparisons MUST identify
differences in hardware, workload, seed volume, service replicas, broker configuration, and
database provisioning; material differences MUST NOT be presented as proof that architecture
alone improved performance.

Before migration, a reproducible baseline of the prior architecture MUST be recorded.
Performance work MUST measure request-path service time, broker publish-to-consume latency,
consumer lag, database query/lock waits, deadlock retries, connection pool waits, and resource
saturation sufficiently to locate bottlenecks. Adding microservices, databases, brokers, or
replicas MUST NOT itself be treated as a demonstrated speed improvement. Analytics MUST remain
off the synchronous checkout path. Avoidable per-item messages, repeated polling of already
processed data, and excessive database round trips MUST be evaluated against measured
bottlenecks. Caching, projections, batching, partitioning, and scaling MUST preserve the
contract, price policy, durability, and stock invariants.

The submission MUST document service artifacts, private stores and permissions, the topic and
message catalog, completion saga transitions and recovery, outbox delivery, Analytics filter
order, and performance comparisons. Evidence MUST include retries, duplicate delivery, service
restarts, broker restarts, and failures between Inventory commit and Checkout finalization.

Rationale: Event-driven microservices add broker hops and operational costs. Measurements and
failure evidence are required to show useful performance without weakening correctness.

## Event Broker, Private Databases & Deployment Constraints

Apache Kafka is the designated event broker. Replacing it, or adding a second broker, requires
an amendment. Broker topics MUST be durable, and each topic MUST document its owner (the only
service that publishes to it), its consumers, its partition key, its retention or compaction
policy, and its message schema versions. Catalog product topics MUST be log-compacted or retain
enough history to rebuild every consumer's projection.

Every message MUST carry a unique message ID, message type, schema version, producer, partition
key, and occurrence timestamp; commands and replies MUST also carry a correlation ID. Partition
keys MUST provide the ordering the consumer depends on, including the accepted-scan ordering
Analytics needs for reproducible hopping windows. Poison messages MUST be retried with bounded
backoff and then moved to a visible dead-letter topic; they MUST NOT be dropped or block a
partition indefinitely.

Services that update business state and publish a message MUST write the outgoing record in the
same local transaction as that state, using a transactional outbox or an explicitly documented
equivalent with the same durability guarantee. Only the owning service or its relay may read
its outbox. Receivers MUST handle redelivery idempotently, with inbox/deduplication or
checkpoint changes and their business writes committed atomically. Offsets MUST be committed
only after durable processing. Outbox relays and consumers MUST resume from durable progress
and MUST NOT repeatedly select a fixed oldest batch in a way that prevents newer records from
being processed. A service MUST NOT hold a database transaction or stock row lock open while
waiting for a broker reply.

Catalog, Checkout, Inventory, and Analytics MUST each use an independently provisionable private
database. Separate MySQL instances or containers are the initial deployment design; using the
same engine does not require sharing an instance. Database access MUST be restricted to the
owning service's account. Credentials MUST be supplied through deployment configuration rather
than committed application secrets. Each service MUST apply only its own migrations.

Shared code MAY contain versioned message contracts and infrastructure helpers. It MUST NOT
contain shared domain repositories, mutable domain state, or business rules that bypass service
ownership. Message schemas MUST evolve compatibly so services can deploy independently without
a synchronized rebuild or deployment of all producers and consumers.

Each service MUST package independently as a runnable JAR or container image, with startup
configuration, health/readiness checks that include broker connectivity, owned migrations, and
graceful shutdown. Deployment configuration MUST identify the broker and the gateway's upstream
services; domain services MUST NOT be configured with each other's endpoints. An Analytics
failure or restart MUST NOT prevent Checkout from accepting valid requests; its events MUST
remain durably pending until delivery recovers. Inventory-dependent completion MUST NOT report
success while Inventory's outcome remains unconfirmed.

## Development Workflow & Quality Gates

A microservice implementation is submittable only after all seven gates pass:

1. **Independent ownership and deployment** - every required service builds, starts, migrates,
   and deploys independently with its own private database and least-privilege credentials.
2. **Event-only service boundaries** - automated checks show that no domain service holds an
   HTTP or other direct client for, or the address of, another domain service, and that no
   service accesses another service's database. Integration evidence verifies the documented
   topic and message contracts. The gateway has no datasource, business rules, response
   aggregation, or completion orchestration.
3. **Contract conformance** - every documented public endpoint, response shape, status code,
   and synchronous outcome is preserved, including partial completion and retry behavior.
4. **Normal, stress, and performance evidence** - the unchanged client's default run and its
   `--stations=100 --duration=120` stress run produce committed JSON reports, with a comparable
   baseline, latency percentiles, throughput, errors, environment details, and evidence
   supporting any claimed performance improvement.
5. **Inventory and saga recovery** - concurrent completion, repeated attempts, lost or delayed
   replies, and crashes between saga steps produce no overselling or duplicate sales. After
   recovery, stock reconciles with completed receipt quantities and no unexplained attempts
   remain.
6. **Event durability** - business writes and their outbox records commit atomically; duplicate
   delivery, consumer restarts, and broker outages do not lose or double-apply messages.
   Projections rebuild from replay, recovery proceeds beyond the first batch, and required
   ordering is preserved.
7. **Submission artifacts** - both reports, the architecture diagram, and concise deployment,
   ownership, topic catalog, workflow, permission, reconciliation, and data-flow documentation
   are committed with the implementation.

Changes to the public contract, load client, mock server, required service list, designated
broker, private data ownership, or mandatory coordination guarantees require an amendment
before implementation. Historical implementation artifacts MAY retain their original
architecture, but the active specification and plan MUST identify conflicts and migration
obligations before implementation. Generic templates read this constitution at runtime and need
not duplicate it.

## Governance

This constitution supersedes conflicting ad-hoc practice in this repository. Feature
specifications and plans MUST demonstrate compliance before implementation begins. Amendments
MUST state a rationale, bump the version, update the amendment date, and identify affected
measurements or migration obligations.

Versioning follows semantic versioning: MAJOR for incompatible changes to service boundaries,
communication or coordination rules, the public contract, or mandatory quality gates; MINOR for
new principles or materially expanded governance; PATCH for clarifications that do not change
obligations. Compliance review MUST verify all seven quality gates before a microservice
submission is accepted. Complexity that does not support a domain boundary, the contract, or a
stated quality gate MUST be removed.

**Version**: 4.0.0 | **Ratified**: 2026-09-17 | **Last Amended**: 2026-10-31
