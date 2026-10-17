# Feature Specification: Database-Coordinated Domain Services

**Feature Branch**: `004-domain-services`

**Created**: 2026-10-15

**Status**: Draft

**Input**: User description: "Refactor the current self-checkout system into independently
deployable domain services governed by the new constitution."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Complete a Checkout Through the Distributed System (Priority: P1)

A shopper starts a transaction, scans items, and completes the purchase through the existing
public checkout experience. The result remains synchronous and contract-compatible even though
the underlying business capabilities run as separate deployable units.

**Why this priority**: Preserving the checkout experience is the system's primary customer value.

**Independent Test**: Start the required business units and gateway, then use the unchanged
checkout client to start a transaction, scan available and unavailable items, and complete it.

**Acceptance Scenarios**:

1. **Given** all required business units are available, **When** a shopper completes a basket of
   available items, **Then** the shopper receives the documented receipt and stock is reduced
   exactly by the fulfilled quantities.
2. **Given** a basket includes unavailable and available items, **When** the shopper completes
   it, **Then** available items complete, unavailable items remain available for a later attempt,
   and the result identifies every unavailable item.
3. **Given** the Analytics unit is restarting or temporarily unavailable, **When** a shopper
   performs a valid checkout action, **Then** the synchronous checkout result remains available
   and the accepted scan remains eligible for later analytics processing.

---

### User Story 2 - View Catalog, Inventory, and Popularity Information (Priority: P2)

An operator uses the existing public endpoints to view the catalog, low-stock alerts, and popular
items while the corresponding business capabilities retain independent ownership of their data.

**Why this priority**: Operators need the same complete view without knowing the internal service
boundaries.

**Independent Test**: Request catalog, low-stock, and popular-item views through the gateway
after scanning a known sequence and verify the documented response shapes and current data.

**Acceptance Scenarios**:

1. **Given** a catalog item exists, **When** an operator requests the catalog, **Then** the
   documented item information is returned.
2. **Given** stock falls below the requested threshold, **When** an operator requests low-stock
   alerts, **Then** every qualifying item is reported with its current quantity.
3. **Given** accepted scans have reached a publication point, **When** an operator requests
   popular items, **Then** the result contains a complete ranking and its associated window
   boundaries.

---

### User Story 3 - Operate and Verify Independent Service Units (Priority: P3)

An instructor or operator can start, stop, verify, and review each business unit separately while
using one centralized source of business data and observing no direct communication between
domain services.

**Why this priority**: Independent deployability and database-only coordination are the defining
requirements of this architecture.

**Independent Test**: Start Catalog, Checkout, Inventory, and Analytics independently; verify
their readiness, permissions, and data ownership; then inspect the integration evidence for the
absence of direct domain-service calls.

**Acceptance Scenarios**:

1. **Given** the centralized data store is available, **When** an operator starts any required
   domain service, **Then** that service can be independently started and verified as ready.
2. **Given** the domain services are handling a checkout, **When** their interactions are
   inspected, **Then** they coordinate only through documented centralized-data interfaces and
   do not make direct calls to one another.
3. **Given** a reviewer opens the repository, **When** they inspect the architecture evidence,
   **Then** they can identify each deployable unit, its data ownership, its permissions, and the
   flow from accepted scan to published popularity result.

### Edge Cases

- A checkout completion and a competing completion request the final available unit at the same
  time: one or more valid partial completions preserve stock correctness without overselling.
- An accepted scan is recorded while Analytics is stopped: the scan is later processed without
  requiring the shopper to retry and without being counted twice.
- A service restarts after persisting work but before finishing its next action: recovery does not
  duplicate fulfillment, receipts, or analytics contributions.
- A domain service attempts to access data outside its documented ownership or read interface:
  the access is rejected and produces an operator-visible diagnostic.
- The gateway receives a request while a required business unit is unavailable: it returns a
  documented failure rather than an incomplete or misleading response.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide four independently deployable domain-service units named
  Catalog, Checkout, Inventory, and Analytics.
- **FR-002**: The system MUST provide one public entry point that preserves every existing
  self-checkout endpoint, request format, response format, status code, and synchronous
  checkout behavior.
- **FR-003**: The public entry point MUST limit its responsibility to request routing and public
  contract translation; it MUST NOT own catalog, checkout, inventory, or analytics business rules.
- **FR-004**: All domain services MUST use a single centralized relational data store with clear,
  documented ownership boundaries for catalog, checkout, inventory, and analytics data.
- **FR-005**: Each domain service MUST write only data it owns and access another service's data
  only through a documented centralized-data interface with least-privilege permissions.
- **FR-006**: Domain services MUST NOT make direct calls to one another. Direct calls include
  synchronous requests, remote procedure calls, shared in-process application code, and
  messaging intermediaries used for inter-service coordination.
- **FR-007**: Checkout MUST atomically preserve each accepted scan and a durable analytics work
  record before reporting scan success to the shopper.
- **FR-008**: Analytics MUST process durable accepted-scan records without a synchronous
  dependency on Checkout, and MUST prevent duplicate contributions to published rankings.
- **FR-009**: Analytics MUST retain its ordered `ingest → window → rank-and-publish` behavior,
  publish complete ranking snapshots, and safely resume pending work after restart.
- **FR-010**: Checkout and Inventory MUST preserve atomic fulfillment, partial completion, and
  per-SKU inventory correctness under concurrent completion requests.
- **FR-011**: A Catalog, Checkout, Inventory, or Analytics restart MUST have a documented,
  verifiable effect on customer requests and pending work; an Analytics restart MUST NOT block
  valid checkout actions.
- **FR-012**: The repository MUST document every deployable unit, its readiness verification,
  data ownership and permissions, data-coordination flows, and evidence that direct
  domain-service communication is absent.
- **FR-013**: The repository MUST include timestamped normal and stress workload reports that
  record operation outcomes, latency percentiles, throughput, deployment topology, and data
  provisioning relevant to the run.

### Key Entities *(include if feature involves data)*

- **Catalog item**: A sellable SKU with its name and price, owned by Catalog.
- **Checkout transaction**: A shopper's in-progress or completed basket, receipt, and customer
  completion state, owned by Checkout.
- **Inventory position**: The available quantity, fulfillment decision, and movement history for
  one SKU, owned by Inventory.
- **Accepted scan record**: The durable record of a successful basket scan that makes analytics
  processing recoverable and traceable.
- **Popularity snapshot**: A complete, published ranking for one analytics window, owned by
  Analytics.
- **Service data interface**: A documented, permission-controlled way for one domain to read or
  coordinate through centrally stored data without a direct service call.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A reviewer can independently start and verify readiness for all four required
  domain services in one documented procedure, with no service requiring another service process
  to be running to become ready.
- **SC-002**: 100% of inspected domain-service interactions in automated integration evidence
  use documented centralized-data interfaces; the evidence contains zero direct calls between
  domain services.
- **SC-003**: All existing public contract checks pass through the public entry point with no
  changed endpoint shapes, status codes, or checkout outcomes.
- **SC-004**: During a normal workload, 100% of successful checkout responses retain their
  documented outcome while accepted scans remain recoverable for analytics.
- **SC-005**: Under a stress workload, reconciliation passes for 100% of SKUs: final stock is
  never negative and the stock reduction equals the quantity in completed receipt lines.
- **SC-006**: After an Analytics stop and restart during accepted scan processing, 100% of the
  test's accepted scans appear exactly once in an eligible published window or remain visibly
  pending for the next publication.
- **SC-007**: A reviewer can locate the four deployables, their data boundaries, permissions,
  coordination flows, and workload evidence from the repository in under five minutes.

## Assumptions

- The existing OpenAPI contract and unchanged load client remain the public compatibility target.
- A single centralized relational data store is available to all required services.
- The public entry point is an allowed transport-level exception to the no-direct-domain-service
  communication rule.
- The feature includes migration from the current in-memory layered implementation; it does not
  introduce customer authentication, payment processing, or new public endpoints.
- The first release uses Catalog, Checkout, Inventory, and Analytics as the required service set;
  adding or removing a domain service requires a constitution amendment.
