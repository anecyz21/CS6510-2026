<!--
Sync Impact Report (review material; remove before committing this amendment)
- Version change: 2.0.0 -> 3.0.0 (incompatible data ownership and coordination rules).
- Modified principles:
  - III. Independently Deployable Domain Services & Database-Only Coordination ->
    III. Independently Deployable Microservices & Private Data Ownership.
  - IV. Inventory Correctness & Partial Completion ->
    IV. Inventory Correctness, Partial Completion & Durable Coordination.
  - V. Evidence-Based Evaluation & Distributed-Service Artifacts ->
    V. Evidence-Based Performance & Microservice Artifacts.
- Added sections: Private Databases, Communication & Deployment Constraints.
- Removed sections: Shared Database Boundaries & Deployment Constraints (replaced).
- Related changes: Principle I clarifies unresolved completion responses; quality gates now
  verify private databases, service contracts, workflow recovery, and performance evidence.
- Migration obligations: Replace cross-schema Catalog reads, Inventory procedure calls,
  Analytics outbox reads, and the no-direct-domain-call guard with API/event boundaries.
  Update feature specifications, plans, data models, deployment documentation, and boundary
  tests before implementing the microservice redesign. Runtime templates are unchanged.
- Follow-up: architecture-characteristics.md currently describes all-or-nothing completion
  and a shared transaction; reconcile it with retained partial completion and saga recovery
  during feature specification. This existing user-modified file was not changed here.
- Deferred placeholders: None.
-->

# Self-Checkout Semester Project Constitution

## Core Principles

### I. API Contract is Authoritative

The provided OpenAPI contract defines the externally visible behavior of the system.
Implementations MUST preserve the documented endpoints, request/response formats, status
codes, and synchronous client-facing behavior. Internal deployment boundaries MAY change
without changing the public contract. A completion response MUST report only confirmed
fulfillment; accepting a workflow or publishing a command is not a successful completion.
Timeout and retry behavior MUST be documented within the existing public contract. Introducing
a pending response or asynchronous public completion API requires an explicit contract amendment.

Rationale: A stable public contract allows different architectures to be measured by the
same client and compared fairly, without hiding unfinished work behind faster responses.

### II. Test Harness Consistency

The load client and mock server are measuring instruments, not service implementation code.
Implementations MUST use them unmodified; only documented client flags MAY vary per run.
Every measured run MUST record the service version, deployment topology, database settings
that affect data volume, and workload parameters.

Rationale: A consistent instrument and recorded environment make throughput and latency
measurements interpretable across machines and architectural revisions.

### III. Independently Deployable Microservices & Private Data Ownership

The system MUST consist of separately buildable, runnable, and deployable microservices:
Catalog, Checkout, Inventory, and Analytics. Each service MUST own a private data store,
credentials, and migrations. Other services MUST NOT access that store through SQL queries,
cross-schema joins, foreign keys, views, stored procedures, or another service's outbox.
Shared identifiers such as SKU, transaction ID, and completion-attempt ID link records
logically; they MUST NOT create cross-database integrity constraints.

Services MUST exchange information through documented, versioned APIs or durable messages.
An API gateway MUST preserve the single public HTTP surface and MUST contain only routing,
contract translation, authentication if introduced, and transport-level concerns. It MUST NOT
own domain data or coordinate business workflows.

Each microservice MUST have a single documented business responsibility:

- Catalog owns item definitions, SKU validity, and authoritative prices.
- Checkout owns transaction lifecycle, baskets, captured purchase prices, receipts, and durable
  completion workflow state. Checkout MUST orchestrate completion across Checkout and Inventory.
- Inventory owns stock, reservations, fulfillment decisions, and inventory movement records.
- Analytics owns accepted-scan consumption, hopping windows, ranking, and published snapshots.

Checkout MAY read products through Catalog's API or maintain a local projection from Catalog
events. Any projection MUST have a documented freshness, versioning, and recovery policy.
Checkout MUST retain the purchase-price snapshot used for each basket or receipt line.
Analytics MUST consume accepted-scan events without synchronous participation in checkout.
Its named internal pipeline MUST remain `ingest -> window -> rank-and-publish`, with one
responsibility per filter and only adjacent internal pipes. Shutdown MUST drain accepted work
or leave it durably recoverable for restart.

Rationale: Private stores and explicit contracts allow a service and its database to evolve,
restart, and deploy without granting other services access to internal persistence structures.

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
of local transactions, not a shared database transaction. Checkout's completion saga MUST:

- Freeze or version the basket for the attempt so concurrent scans and completions cannot
  change the fulfillment request while it is being processed.
- Persist workflow progress and use a stable, unique completion-attempt ID distinct from the
  transaction ID, because a partially fulfilled transaction can have later completion attempts.
- Reserve available quantities in Inventory, durably record the fulfillment and receipt intent
  in Checkout, commit the reservation in Inventory, and finalize Checkout's receipt and basket.
- Make commands and retries idempotent, preventing duplicate decrements and receipt lines.
- Treat timeouts as unknown outcomes; query or retry the same operation before deciding whether
  to compensate. Before inventory commit, a cancelled attempt MUST release its reservation.
  After inventory commit, recovery MUST finish the corresponding Checkout record; timeout alone
  MUST NOT trigger stock restoration or a second sale attempt.
- Define restart recovery and bounded retry/backoff policies, surface unresolved attempts for
  recovery, and release abandoned reservations through a durable cancellation/recovery policy.
  Reservation expiry, if introduced, MUST be coordinated with commit to prevent contradictory
  outcomes. Permanent failures MUST remain visible rather than silently losing work.

No single local transaction makes the entire saga atomic across databases. Reconciliation MUST
verify both the settled inventory invariant and the absence of unexplained in-flight attempts
or abandoned reservations after normal, stress, and injected-failure workloads.

Rationale: Local stock enforcement prevents overselling; durable orchestration and idempotency
preserve correct outcomes despite crashes, duplicate requests, and ambiguous network failures.

### V. Evidence-Based Performance & Microservice Artifacts

Every submission MUST include timestamped normal- and stress-workload JSON reports committed
with the code and reachable from the submitted repository URL. Analysis MUST report mean, p95,
and p99 latency, throughput, errors, workload configuration, and deployment topology. Non-zero
errors MUST be explained. Comparisons MUST identify differences in hardware, workload, seed
volume, service replicas, and database provisioning; material differences MUST NOT be presented
as proof that architecture alone improved performance.

Before migration, a reproducible service-branch baseline MUST be recorded. Performance work
MUST measure request-path service time, database query/lock waits, deadlock retries, connection
pool waits, and resource saturation sufficiently to locate bottlenecks. Adding microservices,
databases, or replicas MUST NOT itself be treated as a demonstrated speed improvement.
Analytics MUST remain off the synchronous checkout path. Avoidable per-item remote calls,
repeated polling of already processed data, and excessive database round trips MUST be evaluated
against measured bottlenecks. Caching, projections, batching, and scaling MUST preserve the
contract, price policy, durability, and stock invariants.

The submission MUST document service artifacts, private stores and permissions, API/event
contracts, completion saga transitions and recovery, outbox delivery, Analytics filter order,
and performance comparisons. Evidence MUST include retries, duplicate delivery, service
restarts, and failures between Inventory commit and Checkout finalization.

Rationale: Microservices add network and operational costs. Measurements and failure evidence
are required to demonstrate useful performance improvements without weakening correctness.

## Private Databases, Communication & Deployment Constraints

Catalog, Checkout, Inventory, and Analytics MUST each use an independently provisionable private
database. Separate MySQL instances or containers are the initial deployment design; using the
same engine does not require sharing an instance. Database access MUST be restricted to the
owning service's account. Credentials MUST be supplied through deployment configuration rather
than committed application secrets. Each service MUST apply only its own migrations.

Services that update business state and publish an event or command MUST write the outgoing
record in the same local transaction as that state, using a transactional outbox or an explicitly
documented equivalent with the same durability guarantee. Only the owning service or its relay
may read its outbox. A durable broker MUST deliver accepted-scan events to Analytics. Receivers
MUST handle redelivery idempotently, with deduplication/checkpoint changes and their business
writes committed atomically. Acknowledgment MUST follow durable processing. Ordering keys,
event IDs, schema versions, retry behavior, and replay/recovery rules MUST be documented;
Analytics MUST preserve the accepted-scan ordering required for reproducible hopping windows.

Synchronous calls MUST define timeouts and bounded retries. A caller MUST NOT hold a database
transaction or stock row lock open while waiting for a remote service. Event consumers and
outbox relays MUST resume from durable progress and MUST NOT repeatedly select a fixed oldest
batch in a way that prevents newer records from being processed.

Shared code MAY contain versioned transport contracts and infrastructure helpers. It MUST NOT
contain shared domain repositories, mutable domain state, or business rules that bypass service
ownership. Compatible contract evolution MUST allow services to deploy independently without
requiring a synchronized rebuild or deployment of all consumers.

Each service MUST package independently as a runnable JAR or container image, with startup
configuration, health/readiness checks, owned migrations, and graceful shutdown. Deployment
configuration MUST identify service endpoints and broker dependencies. An Analytics failure or
restart MUST NOT prevent Checkout from accepting valid synchronous requests; its events MUST
remain durably pending until delivery recovers. Inventory-dependent completion MUST not report
success while Inventory's outcome remains unconfirmed.

## Development Workflow & Quality Gates

A microservice implementation is submittable only after all seven gates pass:

1. **Independent ownership and deployment** - every required service builds, starts, migrates,
   and deploys independently with its own private database and least-privilege credentials.
2. **API and event boundaries** - automated checks and integration evidence prohibit direct
   access to another service's database and verify documented API/event contracts. The gateway
   has no datasource, business rules, or completion orchestration.
3. **Contract conformance** - every documented public endpoint, response shape, status code,
   and synchronous outcome is preserved, including partial completion and retry behavior.
4. **Normal, stress, and performance evidence** - unchanged-client workloads produce reports
   and a comparable baseline with latency percentiles, throughput, errors, environment details,
   and evidence supporting any claimed performance improvement.
5. **Inventory and saga recovery** - concurrent completion, repeated attempts, lost responses,
   and crashes between saga steps produce no overselling or duplicate sales. After recovery,
   stock reconciles with completed receipt quantities and no unexplained attempts remain.
6. **Event and Analytics durability** - accepted scans and outbox records commit atomically;
   duplicate delivery and consumer/broker outages do not lose or double-count scans. Recovery
   processes beyond the first event batch and preserves required window ordering.
7. **Submission artifacts** - reports and concise deployment, ownership, contract, workflow,
   permission, reconciliation, and data-flow documentation are committed with the implementation.

Changes to the public contract, load client, mock server, required service list, private data
ownership, or mandatory coordination guarantees require an amendment before implementation.
Historical implementation artifacts MAY retain their original architecture, but the active
microservice specification and plan MUST identify conflicts and migration obligations before
implementation. Generic templates read this constitution at runtime and need not duplicate it.

## Governance

This constitution supersedes conflicting ad-hoc practice in this repository. Feature
specifications and plans MUST demonstrate compliance before implementation begins. Amendments
MUST state a rationale, bump the version, update the amendment date, and identify affected
measurements or migration obligations.

Versioning follows semantic versioning: MAJOR for incompatible changes to service boundaries,
coordination rules, the public contract, or mandatory quality gates; MINOR for new principles or
materially expanded governance; PATCH for clarifications that do not change obligations.
Compliance review MUST verify all seven quality gates before a microservice submission is
accepted. Complexity that does not support a domain boundary, the contract, or a stated quality
gate MUST be removed.

**Version**: 3.0.0 | **Ratified**: 2026-09-17 | **Last Amended**: 2026-10-23
