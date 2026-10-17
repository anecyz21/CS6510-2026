<!--
Sync Impact Report
- Version change: 1.3.0 → 2.0.0
- Modified principles: III. Layered Architecture, Isolation & Pipeline Composition →
  III. Independently Deployable Domain Services & Database-Only Coordination;
  V. Evidence-Based Evaluation & Submission Artifacts →
  V. Evidence-Based Evaluation & Distributed-Service Artifacts
- Added sections: Shared Database Boundaries & Deployment Constraints
- Removed sections: None
- Follow-up TODOs: Define the implementation feature specification and migration plan before
  replacing the current layered server.
-->

# Self-Checkout Semester Project Constitution

## Core Principles

### I. API Contract is Authoritative

The provided OpenAPI contract defines the externally visible behavior of the system.
Implementations MUST preserve the documented endpoints, request/response formats, status
codes, and synchronous client-facing behavior. Internal deployment boundaries MAY change
without changing the public contract.

Rationale: A stable public contract allows different architectures to be measured by the
same client and compared fairly.

### II. Test Harness Consistency

The load client and mock server are measuring instruments, not service implementation code.
Implementations MUST use them unmodified; only documented client flags MAY vary per run.
Every measured run MUST record the service version, deployment topology, database settings
that affect data volume, and workload parameters.

Rationale: A consistent instrument and recorded environment make throughput and latency
measurements interpretable across machines and architectural revisions.

### III. Independently Deployable Domain Services & Database-Only Coordination

The system MUST consist of separately buildable, runnable, and deployable domain-service
units: Catalog, Checkout, Inventory, and Analytics. An API gateway MAY preserve the single
public HTTP surface, but it is not a domain service and MUST contain only routing, contract
translation, authentication if introduced, and transport-level concerns.

Domain services MUST NOT communicate directly with one another through HTTP, RPC, in-process
method calls, shared application classes, queues, or brokers. They coordinate exclusively
through the centralized database, using schema-owned data, database transactions, outbox
tables, views, or stored procedures where needed. In particular, Checkout MUST persist an
accepted scan and its analytics outbox record atomically; Analytics MUST consume that durable
record without a synchronous call from Checkout. Any exception to this rule requires an
explicit constitution amendment that identifies the direct dependency, failure behavior, and
why database coordination cannot satisfy the requirement.

Each domain service MUST have a single documented business responsibility:

- Catalog owns item definitions, SKU validity, and prices.
- Checkout owns transaction lifecycle, baskets, receipts, and customer-facing completion state.
- Inventory owns stock, reservations where used, fulfillment decisions, and inventory movement
  records.
- Analytics owns accepted-scan consumption, hopping windows, ranking, and published snapshots.

The Analytics service MUST retain the named internal pipeline `ingest → window →
rank-and-publish`. Each filter MUST have one responsibility, use only adjacent internal pipes,
and drain accepted work before shutdown completes.

Rationale: Separate deployables make business ownership and operational boundaries explicit;
database-only coordination avoids hidden, synchronous coupling between services while retaining
a coherent source of truth.

### IV. Inventory Correctness & Partial Completion

Stock is decremented when a transaction completes, not when an item is scanned. For every SKU,
the drop in stock across a run MUST equal the quantity in completed receipt lines, and stock
MUST never be negative. Completion and stock mutation MUST execute atomically in the
centralized database through a documented transaction boundary or stored procedure. The
invariant MUST be verified under stress load rather than inferred from functional tests.

When one or more scanned items are unavailable at completion, the system MUST complete every
independently available line item, MUST NOT decrement stock for unavailable items, MUST retain
each unavailable item in the basket for a later attempt, and MUST identify unavailable items in
the customer-facing result. An unavailable line item MUST NOT cause otherwise available items
to be rejected.

Rationale: Atomic fulfillment prevents overselling under concurrency, while partial completion
preserves valid customer purchases despite stock shortages.

### V. Evidence-Based Evaluation & Distributed-Service Artifacts

Every submission MUST include timestamped normal- and stress-workload JSON reports committed
with the code and reachable from the submitted repository URL. Analysis MUST report mean, p95,
and p99 latency, throughput, errors, the workload configuration, and the deployment topology.
Non-zero errors MUST be explained. Results SHOULD be compared only with runs that use
comparable hardware, workload parameters, service topology, and database provisioning.

The submission MUST document each service's deployable unit, database schema ownership,
service database permissions, the Checkout-to-Analytics outbox flow, the atomic Checkout and
Inventory completion boundary, and the Analytics filter order. Integration evidence MUST show
that domain-service processes do not make direct calls to one another.

Rationale: Distributed boundaries create new correctness and operational risks that cannot be
validated from unit tests or mean latency alone.

## Shared Database Boundaries & Deployment Constraints

All domain services MUST use one centralized relational database, divided into separately owned
schemas or equivalently enforced table namespaces: `catalog`, `checkout`, `inventory`, and
`analytics`. A service MUST write only its owned schema. It MUST access another service's data
only through a documented database interface such as a read-only view, stored procedure, or
durable outbox/inbox table. Database credentials MUST enforce these permissions where the
database supports them.

Shared code is limited to a versioned database-infrastructure library containing connection
configuration, migration support, transaction utilities, common identifiers, and observability
helpers. It MUST NOT contain cross-domain repositories, business rules, domain entities, or a
general-purpose store abstraction that lets one service mutate another service's data.

Each service MUST package independently as a runnable deployable unit, such as an executable
JAR or container image, with its own startup configuration, health endpoint or equivalent
readiness check, database migration ownership, and graceful shutdown behavior. A failure or
restart of Analytics MUST NOT prevent Checkout from accepting valid synchronous requests.

## Development Workflow & Quality Gates

A distributed-service implementation is submittable only after all gates pass:

1. **Service boundaries** — each required domain service independently builds, starts, and
   connects to the centralized database with only its authorized permissions.
2. **No direct service calls** — automated inspection and integration evidence show that domain
   services coordinate only through the database; the gateway contains no business logic.
3. **Contract conformance** — the gateway preserves every documented endpoint, response shape,
   and status code.
4. **Normal and stress runs** — both workloads complete with reports that preserve operation
   outcomes, latency percentiles, throughput, and deployment details.
5. **Inventory invariant** — Principle IV passes against the stress run's final database state.
6. **Analytics durability** — accepted scans are atomically recorded, Analytics processes them
   exactly once or idempotently, and shutdown/restart evidence shows no silent loss.
7. **Submission artifacts** — reports and concise service, schema, permission, and data-flow
   documentation are committed with the implementation.

Changes to the public contract, load client, mock server, service list, or no-direct-call rule
require an amendment before implementation.

## Governance

This constitution supersedes conflicting ad-hoc practice in this repository. Feature
specifications and plans MUST demonstrate compliance before implementation begins. Amendments
MUST state a rationale, bump the version, update the amendment date, and identify affected
measurements or migration obligations.

Versioning follows semantic versioning: MAJOR for incompatible changes to service boundaries,
coordination rules, the public contract, or mandatory quality gates; MINOR for new principles or
materially expanded governance; PATCH for clarifications that do not change obligations.
Compliance review MUST verify all seven quality gates before a distributed-service submission is
accepted. Complexity that does not support a domain boundary, the contract, or a stated quality
gate MUST be removed.

**Version**: 2.0.0 | **Ratified**: 2026-09-17 | **Last Amended**: 2026-10-15
