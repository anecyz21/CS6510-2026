# Feature Specification: Event-Driven Communication Between Checkout Microservices

**Feature Branch**: `event-based`

**Created**: 2026-10-31

**Status**: Draft

**Input**: User description: "Migrate all communication between Catalog, Checkout, Inventory
and Analytics to Kafka events per constitution v4.0.0, then produce the default and stress
load-client JSON reports and an architecture diagram."

**Governance**: [Constitution v4.0.0](../../.specify/memory/constitution.md). This feature
builds on [004-domain-services](../004-domain-services/spec.md). Every public behavior clarified
there stays in force: partial completion with per-attempt receipts, `409 INSUFFICIENT_STOCK`,
`409 TRANSACTION_BUSY`, `409 TRANSACTION_NOT_OPEN`, and `503 SERVICE_UNAVAILABLE` for completions
that cannot be confirmed in time. This feature changes only how the services talk to each other.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Check Out While Services Talk Only Through the Broker (Priority: P1)

A shopper starts a transaction, scans items, and completes a purchase through the existing
public entry point, exactly as before. Behind that entry point, no domain service calls another
one directly: product information reaches Checkout and the other services as events, and
Checkout settles each purchase with Inventory by exchanging commands and replies through the
event broker.

**Why this priority**: This is the assignment's core requirement. Every other story depends on
the services communicating only through the broker while shoppers see no change.

**Independent Test**: Run the unchanged load client with default parameters against the public
entry point, then inspect each domain service's configuration and dependencies. The run has no
errors, and no domain service holds another domain service's address or a client for it.

**Acceptance Scenarios**:

1. **Given** the four services, the gateway, and the broker are running, **When** a shopper
   starts a transaction, scans valid items, and completes it, **Then** the responses match the
   public contract and the receipt reflects the captured prices.
2. **Given** a scan of a valid SKU, **When** Checkout validates and prices it, **Then** it uses
   its own copy of the catalog, built from Catalog's events, without asking Catalog at scan time.
3. **Given** a scan of a SKU that Catalog has never published, **When** the shopper scans it,
   **Then** the existing unknown-item error is returned and the basket does not change.
4. **Given** a completion request, **When** Checkout settles it, **Then** reservation, commit,
   and any release happen through commands sent to Inventory and replies sent back, all over
   the broker, and the response reports only quantities Inventory confirmed.
5. **Given** the deployed system, **When** a reviewer inspects each domain service's
   configuration, **Then** the only connection details it holds are for its own database and
   the broker. Only the gateway holds service addresses, and it uses them only to forward
   public requests.

---

### User Story 2 - Purchases Stay Correct When Messages Are Lost, Late, or Repeated (Priority: P1)

A shopper's purchase is sold at most once and recorded once, even when the broker restarts, a
service restarts mid-purchase, a reply arrives after the shopper's request has timed out, or a
message is delivered more than once.

**Why this priority**: Moving to messages replaces request-and-response with delivery that can
be delayed, repeated, or interrupted. Correct stock and receipts must survive all of it.

**Independent Test**: For a known completion, interrupt delivery or restart a service before
the reserve reply, after the reserve reply, and after the commit reply. Deliver every message
type twice. Then reconcile stock against receipts.

**Acceptance Scenarios**:

1. **Given** Inventory is stopped, **When** a shopper completes a purchase, **Then** the request
   returns `503 SERVICE_UNAVAILABLE` within the configured timeout, and the purchase attempt
   stays recorded so it can resume.
2. **Given** an attempt received `503`, **When** Inventory comes back and replies, **Then** the
   attempt finishes once: committed quantities produce exactly one receipt, or the attempt is
   released with no stock change. A later completion call does not sell those quantities again.
3. **Given** any command or reply is delivered twice, **When** the receiver processes it,
   **Then** the second delivery has no effect on stock, reservations, receipts, or analytics.
4. **Given** the broker restarts during a load run, **When** it is available again, **Then** no
   accepted scan, completion attempt, or reply is lost, and waiting messages are delivered.
5. **Given** Checkout restarts after publishing a reserve command but before the reply arrives,
   **When** it starts again, **Then** it resumes the same attempt with the same identity rather
   than starting a competing one.
6. **Given** a message that keeps failing to process, **When** its retry limit is reached,
   **Then** it is set aside where operators can see it, and later messages keep flowing.

---

### User Story 3 - One Service Being Down Does Not Stop the Others (Priority: P2)

An operator can stop or restart any one domain service without stopping the others. The
stopped service catches up on missed messages when it returns.

**Why this priority**: Decoupling services in time is the main operational benefit of messaging,
and it must be demonstrated rather than assumed.

**Independent Test**: While a workload runs, stop Catalog, then Analytics, then Inventory, one
at a time, for 60 seconds each. Record which public operations keep working and confirm that
each service catches up after it restarts.

**Acceptance Scenarios**:

1. **Given** Catalog is stopped, **When** shoppers scan items already published, **Then** scans
   succeed with correct prices, because Checkout uses its own copy of the catalog.
2. **Given** Analytics is stopped, **When** shoppers scan and complete purchases, **Then** both
   succeed, and Analytics processes every missed scan once after it restarts.
3. **Given** Inventory is stopped, **When** shoppers scan, **Then** scans succeed. Completions
   return `503 SERVICE_UNAVAILABLE` until Inventory returns, then succeed again.
4. **Given** a service starts with an empty copy of the catalog, **When** it starts up, **Then**
   it rebuilds the copy from Catalog's published events and reports not ready until it has
   caught up.

---

### User Story 4 - Submit Load-Test Reports and an Architecture Diagram (Priority: P2)

An instructor opens the submitted repository and finds two load-client reports from the
event-driven system, one with default parameters and one from the stress run
(100 stations for 120 seconds), plus a diagram of the services and the broker.

**Why this priority**: These three artifacts are the graded deliverables.

**Independent Test**: From the repository's entry point, follow links to both reports and the
diagram, then check each report's recorded workload parameters and the diagram's contents.

**Acceptance Scenarios**:

1. **Given** the event-driven system is deployed, **When** the unchanged load client runs with
   default parameters, **Then** its JSON report is committed under a stable, linked path.
2. **Given** the same deployment, **When** the load client runs with `--stations=100
   --duration=120`, **Then** its JSON report is committed under a stable, linked path.
3. **Given** the committed reports, **When** a reviewer reads them, **Then** each records its
   stations, duration, operation counts, errors, mean, p95, and p99 latency, and throughput, and
   the accompanying notes record the deployment topology and explain any errors.
4. **Given** the architecture diagram, **When** a reviewer reads it, **Then** it shows the
   gateway, all four services, each service's private database, the broker, and every message
   flow between services, with its direction and the topic it uses.

---

### User Story 5 - Operator Views Stay Correct (Priority: P3)

An operator views catalog items, low-stock items with their product names, and popular items.
The results are the same as before the migration, even though product names now reach
Inventory and Analytics as events rather than through direct requests.

**Why this priority**: These views must not regress, but they are lower risk than purchases.

**Independent Test**: Seed a known catalog and stock, accept a known sequence of 1,500 scans,
and compare the catalog, low-stock, and popular-item views with the expected results.

**Acceptance Scenarios**:

1. **Given** known products and stock, **When** low-stock items are requested, **Then** names
   and physical stock counts match Catalog's and Inventory's data.
2. **Given** 1,500 accepted scans in a known order with default window settings, **When** the
   publication at scan 1,500 completes, **Then** the ranking reflects scans 501 through 1,500
   and the popular-item view shows the correct product names.

### Edge Cases

- **Broker fully unavailable**: Scans whose catalog data is already held locally still succeed,
  and their analytics events wait to be sent. Completions return `503 SERVICE_UNAVAILABLE`.
  Nothing is lost when the broker returns.
- **Reply after the request timed out**: The attempt continues in the background and finishes
  once. The shopper's next completion call sees the settled result: `409 TRANSACTION_NOT_OPEN`
  if the basket is now empty, or a new attempt for only the quantities still remaining.
- **Reply for an unknown or already settled attempt**: The reply is recorded and ignored, with
  no change to stock or receipts.
- **Commit and release race**: Exactly one wins. A released reservation can never be committed
  later, and a committed one is never released.
- **Message with a newer schema version**: Consumers ignore fields they don't recognize and keep
  processing. An incompatible version is set aside where operators can see it, not applied.
- **Catalog copy not yet built at startup**: The service reports not ready until its copy has
  caught up. Requests that need a product it doesn't hold yet get `503 SERVICE_UNAVAILABLE`,
  never "unknown item", because a not-ready flag alone doesn't stop traffic from reaching it.
- **A scan for a product Catalog publishes after startup**: Accepted once that product's event
  has been applied; rejected as unknown before then.
- **Duplicate completion requests while an attempt is unresolved**: They join the existing
  attempt and do not send new reserve commands.
- **Backlog after an outage**: Newer messages are not starved behind already processed ones,
  and progress continues past the first batch.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Catalog, Checkout, Inventory, and Analytics MUST communicate with each other only
  through messages carried by the designated event broker. No direct call between domain
  services, and no relaying through the gateway, is allowed.
- **FR-002**: No domain service's configuration may contain another domain service's address.
  Each holds connection details only for its own database and the broker.
- **FR-003**: The gateway MUST continue to expose the single public HTTP surface and forward each
  public request to the one service that owns it. It MUST NOT combine responses from several
  services, hold domain data, or coordinate purchases.
- **FR-004**: All public operations MUST keep their documented requests, responses, status
  codes, and error identifiers, including the behaviors clarified in feature 004.
- **FR-005**: Catalog MUST publish an event for every product it offers, and for every later
  change. Its published events MUST be enough for any consumer to rebuild a complete copy of
  the catalog from scratch.
- **FR-006**: Checkout, Inventory, and Analytics MUST each keep their own copy of the catalog
  data they need, built only from Catalog's events. Each copy MUST record how far it has read
  and MUST be rebuildable by replaying those events.
- **FR-007**: A service MUST NOT report ready until its catalog copy has caught up with the
  events that existed when it started.
- **FR-008**: Checkout MUST validate and price scans from its own catalog copy and keep the
  price captured for each basket line. Later catalog changes MUST NOT change captured prices.
- **FR-009**: Checkout MUST settle each completion attempt by sending reserve, commit, and
  release commands to Inventory and acting on Inventory's replies, all through the broker.
- **FR-010**: Every command and reply in a completion MUST carry the attempt's identity. All
  messages for one attempt MUST be delivered in the order they were sent.
- **FR-011**: Inventory MUST change reservations and stock only in response to commands
  received from the broker. Each command MUST be answered with a reply that reports its outcome.
- **FR-012**: A completion request MAY wait for Inventory's replies up to a configured time
  limit. If the outcome is not confirmed by then, it MUST return `503 SERVICE_UNAVAILABLE`
  while the attempt continues in the background.
- **FR-013**: If an expected reply does not arrive, Checkout MUST resend the same command or ask
  Inventory for the attempt's status before deciding to cancel. A missing reply alone MUST NOT
  restore stock or start a second sale.
- **FR-014**: Every service MUST record each business change and the messages it causes as one
  local outcome, so a crash cannot leave one without the other.
- **FR-015**: Every receiver MUST give each message one business effect, no matter how many
  times it is delivered. It MUST record what it has processed together with that effect, and
  acknowledge a message only after both are saved.
- **FR-016**: Every message MUST carry a unique message identity, a type, a schema version, its
  producer, the key that determines its ordering, and when it occurred.
- **FR-017**: A message that still fails after a bounded number of retries MUST be moved to a
  place where operators can see it. It MUST NOT be dropped, and it MUST NOT block later messages.
- **FR-018**: Accepted scans MUST continue to reach Analytics as events, in the order Analytics
  needs to reproduce its windows. The `ingest -> window -> rank-and-publish` pipeline and its
  default windows (latest 1,000 scans, refreshed every 500) MUST be unchanged.
- **FR-019**: Low-stock and popular-item views MUST show product names taken from the owning
  service's catalog copy, with the same meaning and fields as before.
- **FR-020**: Stopping Catalog, Analytics, or Inventory MUST NOT stop the operations that don't
  depend on it (see User Story 3). Each service MUST catch up on missed messages after a restart.
- **FR-021**: The repository MUST document every topic: which service publishes to it, which
  services read it, its ordering key, how long its messages are kept, and its message versions.
- **FR-022**: Automated checks MUST fail the build if a domain service gains a direct client for,
  or the address of, another domain service.
- **FR-023**: The unchanged load client MUST be run against the event-driven system with default
  parameters, and its JSON report committed to the repository.
- **FR-024**: The unchanged load client MUST be run with `--stations=100 --duration=120`, and its
  JSON report committed to the repository.
- **FR-025**: Notes beside the reports MUST record the deployment topology, broker settings,
  data seeding, hardware, and an explanation of any errors. They MUST also compare the results
  with the feature 004 build re-measured under the same two profiles (see Assumptions), naming
  any differences in workload or environment.
- **FR-026**: The repository MUST contain an architecture diagram showing the gateway, the four
  services, each private database, the broker, and every message flow with its direction and
  topic. It MUST be linked from the repository's main entry point.
- **FR-027**: After each load run, reconciliation MUST confirm that, for every SKU, starting
  stock minus ending stock equals purchased receipt quantities, with no unresolved attempt or
  leftover reservation.
- **FR-028**: Validation MUST cover: duplicate delivery of each message type, a broker restart
  under load, restarting each service mid-purchase, a reply arriving after a timeout, and
  replaying catalog events to rebuild a copy.

### Key Entities

- **Product event**: Catalog's announcement of a product's identity, name, validity, and price,
  with a version so consumers can apply changes in order.
- **Catalog copy**: A service's local view of the products it needs, built from product events,
  with the position it has read up to.
- **Completion command**: A reserve, commit, release, or status request from Checkout to
  Inventory for one attempt, identified by that attempt.
- **Completion reply**: Inventory's report of a command's outcome, such as quantities reserved,
  committed, or released, identified by the same attempt.
- **Accepted-scan event**: Checkout's record that one scan succeeded, with its order position,
  consumed by Analytics. It is unchanged from feature 004.
- **Outgoing message record**: A message saved together with the business change that caused it,
  waiting to be sent. Each service has its own.
- **Processed-message record**: A receiver's record of messages it has already applied, used to
  ignore repeats.
- **Set-aside message**: A message that repeatedly failed processing, kept with its failure
  reason for operators.
- **Topic**: A named, durable channel with one publishing service, one or more readers, an
  ordering key, and a retention rule.
- **Load-test report**: The load client's JSON output for one run, with its notes on topology
  and errors.
- **Architecture diagram**: The submitted picture of the services, databases, gateway, broker,
  and message flows.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An inspection of all four domain services finds no direct client for, and no
  address of, another domain service, and the automated boundary check passes.
- **SC-002**: The default load-client run (10 stations for 60 seconds) completes with zero
  operation errors, and its report is committed and linked.
- **SC-003**: The stress run (100 stations for 120 seconds) completes, at least 99% of its
  operations succeed, and every failure is categorized and explained. Its report is committed
  and linked.
- **SC-004**: After each of the two runs, reconciliation passes for 100% of SKUs, with zero
  unresolved attempts or leftover reservations.
- **SC-005**: With Catalog stopped for 60 seconds, 100% of scans for already published items
  succeed with correct captured prices.
- **SC-006**: With Inventory stopped, every completion returns `503 SERVICE_UNAVAILABLE` within
  the configured time limit. Within 60 seconds of Inventory being healthy again, every affected
  attempt is settled, with zero duplicate sales.
- **SC-007**: When every message type is delivered twice in a controlled test, there are zero
  duplicate stock changes, receipt lines, or analytics counts.
- **SC-008**: After a broker restart during load, zero accepted scans or completion attempts are
  lost, and the backlog is fully processed within 60 seconds of the broker being healthy.
- **SC-009**: For a known 1,500-scan sequence, the publication at scan 1,500 exactly matches the
  expected ranking of scans 501 through 1,500, both before and after a replay.
- **SC-010**: A reviewer starting from the repository's main entry point finds both reports and
  the architecture diagram in under 2 minutes. The diagram shows all four services, the
  gateway, four databases, the broker, and every topic flow.
- **SC-011**: The report notes state the change in scan and completion p95 latency relative to
  the feature 004 reports. Neither direction is presented as an architectural result without
  evidence of comparable workloads and environments.

## Assumptions

- Constitution v4.0.0 designates Apache Kafka as the broker. It is already part of the
  deployment and carries accepted-scan events today. This specification refers to "the broker"
  and leaves topic layout and client settings to planning.
- The gateway's forwarding of public requests stays HTTP; the user confirmed this when the
  constitution was amended. Only traffic between the four domain services must use the broker.
- Operator and test endpoints (attempt inspection, retry, reconciliation export, test stock
  provisioning) are calls from people or tests to one service. They are not communication
  between services, so they may stay HTTP.
- The default run uses the load client's built-in defaults (10 stations for 60 seconds against
  the public entry point). The stress run is exactly `java -cp out Main --stations=100
  --duration=120`. Both use the unmodified client, with enough seeded stock that shortages
  come only from real contention.
- The catalog has no public administration workflow. Products are published at Catalog's
  startup and on seed changes, so catalog copies change rarely and freshness lag is acceptable.
- Today, Checkout, Inventory, and Analytics read Catalog over HTTP, and Checkout reaches
  Inventory over HTTP for reservations and health checks. This feature removes all of those.
- The comparison point is the feature 004 build, re-measured on the same host with the same two
  profiles as this feature's runs and committed under
  `domain-services/deployment/reports/event-driven/baseline-004/`. The existing 004 reports used
  a different stress profile (200 stations for 180 seconds), and the files in
  `load-client/reports/` predate 004, so neither is comparable. No performance improvement
  target is set. Results are reported as measured, per constitution Principle V.
- Payment, refunds, authentication, and new public endpoints remain out of scope.
