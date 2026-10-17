# Research: Database-Coordinated Domain Services

## Decision: Maven reactor with independently executable Spring Boot JARs

Each of Catalog, Checkout, Inventory, Analytics, and the API gateway is a Maven module that
builds one runnable artifact. Docker Compose is the local orchestration option, not a substitute
for independent JAR execution.

**Rationale**: The repository already uses Java 21, Maven, Spring Boot, JDBC, Flyway, and MySQL.
Keeping the toolchain makes the architectural change measurable without introducing unrelated
framework changes.

**Alternatives considered**:

- Keep one Spring Boot application with packages: rejected because it cannot demonstrate
  independent deployability.
- Use a new language or runtime per service: rejected because it confounds the assignment's
  architectural comparison.

## Decision: One MySQL server with service-owned schemas and credentials

The centralized MySQL server hosts `catalog`, `checkout`, `inventory`, and `analytics` schemas.
Each service migrates and writes only its owned schema. Read-only views and narrowly granted
stored-procedure execution are the only cross-domain database interfaces.

**Rationale**: This satisfies centralized storage while preventing a shared repository or broad
database account from becoming hidden cross-service coupling.

**Alternatives considered**:

- One unpartitioned schema shared by all services: rejected because table ownership and write
  permissions cannot be enforced.
- One database per service: rejected because the constitution requires a single centralized
  database.

## Decision: Checkout outbox and Analytics idempotent checkpoint

Checkout writes an accepted scan and an outbox row in the same transaction that updates the
basket. Analytics reads the published outbox view, writes its own processed-record checkpoint
with a unique scan identifier, then updates the ordered window and snapshot.

**Rationale**: Checkout remains available when Analytics is down, and retries do not double-count
scans. No service request, message broker, or shared domain class is needed.

**Alternatives considered**:

- Checkout calling Analytics after scanning: rejected because it is direct service coupling and
  makes checkout depend on Analytics availability.
- A shared message broker: rejected because the constitution prohibits brokers for domain-service
  coordination.

## Decision: Inventory-owned fulfillment stored procedure

Checkout calls a documented Inventory stored procedure from its own database transaction. The
procedure locks inventory rows in SKU order, determines fulfillment, updates stock and movement
records, and returns fulfilled/unavailable quantities. Checkout then updates its own transaction
and receipt records before committing the same transaction.

**Rationale**: Inventory retains ownership of stock mutation while completion remains atomic and
partial completion remains contract-compatible.

**Alternatives considered**:

- Checkout directly updating inventory tables: rejected because it violates Inventory ownership.
- Checkout calling an Inventory HTTP endpoint: rejected because direct domain-service calls are
  prohibited and distributed rollback would weaken correctness.

## Decision: Gateway is the only HTTP router

The API gateway exposes the unchanged public contract and makes transport calls to the owning
service. It contains no domain rules or persistence access. Domain services do not call it or each
other.

**Rationale**: One public base URL keeps the supplied client valid while preserving the
no-direct-domain-service rule.

**Alternatives considered**:

- Expose each service directly to the load client: rejected because it changes the public
  contract.
- Let the gateway query the database directly: rejected because it would contain domain behavior
  and bypass ownership boundaries.
