# Feature Specification: Independently Deployable Checkout Microservices

**Feature Branch**: `microservice-new`

**Created**: 2026-10-15

**Updated**: 2026-10-23

**Status**: Clarified - ready for planning; approved behavior must be reflected in contract documents before implementation

**Input**: User description: "Update the active feature specification based on constitution
v3.0.0: independently deployable microservices with private databases, coordinated completion
across services, reliable analytics events, and measured performance improvements."

**Governance**: [Constitution v3.0.0](../../.specify/memory/constitution.md).
This revision supersedes this feature's database-only coordination requirements. Planning
artifacts for the prior architecture were removed and MUST be regenerated before implementation.

## Clarifications

### Session 2026-10-23

- Q: Should the existing completion response be extended to describe partial fulfillment?
  A: No new shortage fields, objects, or extra messages. Purchase available quantities using
  the existing receipt; short-stock quantities simply remain in the open basket.
- Q: What should completion return when every item in the basket is out of stock?
  A: Return 409 INSUFFICIENT_STOCK using the existing error format; create no receipt,
  change no stock, and leave all items in the unchanged open basket.
- Q: Should receipts show only the current partial purchase or cumulative transaction purchases?
  A: Use per-attempt receipts. Each successful completion returns only its newly purchased
  items and totals, under the same transaction ID used for all partial purchases.
- Q: If Inventory is unavailable during completion, should the request time out or wait for recovery?
  A: Use a bounded timeout and return 503 SERVICE_UNAVAILABLE in the existing error format.
  Preserve the durable attempt for safe recovery; timeout does not imply cancellation.
- Q: Should a scan arriving during an unresolved completion wait or be rejected immediately?
  A: Reject it immediately with 409 TRANSACTION_BUSY using the existing error format;
  do not queue the scan, change the basket, or record an accepted-scan event.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Purchase Available Items Without Overselling (Priority: P1)

A shopper starts a transaction, scans items, and completes a purchase through the existing
public entry point. Available quantities are purchased, unavailable quantities remain in the
basket, and the shopper receives confirmed results even when many stations compete for stock.

**Why this priority**: Correct purchases and inventory are the primary customer value. Separate
service databases must not create duplicate purchases, lost receipts, or negative stock.

**Independent Test**: Use two stations with a known catalog and limited stock; scan repeated
SKUs and competing baskets, complete them, and compare receipts, remaining baskets, and stock.

**Acceptance Scenarios**:

1. **Given** an open transaction and valid SKU with zero stock, **When** the shopper scans it,
   **Then** exactly one unit is added and priced without decrementing or reserving stock.
2. **Given** enough stock for every scanned unit, **When** completion succeeds, **Then** the
   receipt contains the purchased quantities and captured prices, stock decreases exactly by
   those quantities, and the transaction is completed.
3. **Given** a basket requests three units of A and one of B, with two units of A and one of B
   available, **When** completion succeeds, **Then** two A and one B are purchased, one A remains
   in the open basket, and a 200 response in the existing receipt format describes only purchased quantities without
   adding shortage fields, objects, or messages.
4. **Given** the remaining A later becomes available through controlled test provisioning,
   **When** a later completion attempt succeeds, **Then** only that remaining unit is purchased
   and previous purchases are neither deducted nor charged again. Its receipt uses the same
   transaction ID, lists only that one newly purchased A, and excludes earlier purchased units
   from its item count and total. The transaction becomes completed when the basket is empty.
5. **Given** two stations compete for the last unit of a SKU, **When** they complete concurrently,
   **Then** at most one unit is sold and other available basket quantities can still complete.
6. **Given** every requested unit is unavailable, **When** completion is attempted, **Then** no
   stock or purchased receipt quantity changes, the basket remains open and unchanged, and the
   response is `409 INSUFFICIENT_STOCK` using the existing error format. No receipt is created
   and no new shortage fields or additional response structures are introduced.
7. **Given** a missing transaction, closed transaction, or empty basket, **When** the relevant
   public action is attempted, **Then** its existing documented error behavior is preserved.

---

### User Story 2 - Recover a Purchase After an Interrupted Completion (Priority: P1)

A shopper or operator can recover a checkout interrupted by a service restart or lost response
without paying twice, losing purchased items, or permanently holding stock for an abandoned sale.

**Why this priority**: Each service commits its own data independently, so interruptions between
steps must have a durable, recoverable outcome.

**Independent Test**: Interrupt a known completion before reservation, after reservation, after
receipt preparation, and after stock commit; restore the affected service and reconcile results.

**Acceptance Scenarios**:

1. **Given** Inventory reserved quantities but has not committed a sale, **When** Checkout
   restarts, **Then** it resumes the same attempt or durably cancels it and releases reservations;
   it does not create a second competing attempt for the same frozen basket.
2. **Given** Inventory committed stock but its response was lost, **When** Checkout retries or
   recovers, **Then** it discovers the existing outcome and finalizes the matching receipt once.
3. **Given** a sale is finalized but the shopper did not receive the response, **When** the
   completion is retried, **Then** previously purchased quantities are not sold again. A completed
   transaction returns `409 TRANSACTION_NOT_OPEN`; if a partial purchase has already finalized
   and left an open basket, a later call purchases only the remaining quantities in a new attempt.
4. **Given** a completion attempt is unresolved, **When** another scan targets that transaction,
   **Then** it immediately returns `409 TRANSACTION_BUSY` in the existing error format, without
   changing the basket or recording an accepted scan. A concurrent completion retry instead
   attaches to or resumes the same attempt and returns its confirmed result, or the configured
   timeout response, without creating a competing attempt.
5. **Given** an attempt is cancelled before Inventory commits, **When** recovery finishes,
   **Then** all its reservations are released without changing physical stock or receipt totals.
6. **Given** a dependency stays unavailable, **When** bounded recovery attempts are exhausted,
   **Then** the synchronous request returns `503 SERVICE_UNAVAILABLE` within its configured
   timeout and the unresolved attempt remains visible to operators for recovery. The response
   does not imply cancellation; background recovery and safe retries cannot duplicate a sale.

---

### User Story 3 - Deploy Services Without Shared Database Access (Priority: P2)

An operator deploys Catalog, Checkout, Inventory, or Analytics independently and can identify
which service owns each record. A service change does not require another service to access
its internal tables or all services to be released together.

**Why this priority**: Independent deployment and private data ownership define this redesign.

**Independent Test**: Start each service with its own store and configuration, verify readiness,
exercise supported interactions, and attempt unauthorized access to another service's store.

**Acceptance Scenarios**:

1. **Given** a service's private store and declared infrastructure are available, **When** that
   service starts with other domain processes stopped, **Then** its own initialization and
   readiness succeed; operations needing another domain report their dependency truthfully.
2. **Given** a domain service's credentials, **When** access to another service's store is
   attempted, **Then** it is denied, including access to published views, procedures, and outboxes.
3. **Given** a backward-compatible service update, **When** that service is redeployed,
   **Then** unchanged consumers continue using supported contracts without coordinated releases.
4. **Given** a request through the public entry point, **When** its business work is inspected,
   **Then** the owning service performs it; the entry point does not store domain data or
   orchestrate completion.

---

### User Story 4 - View Reliable Catalog, Stock, and Popularity Results (Priority: P2)

An operator views catalog items, low-stock information, and popular items while analytics
processing proceeds independently of customer checkout.

**Why this priority**: Operators need accurate, recoverable views without slowing purchases.

**Independent Test**: Accept a known sequence of at least 1,500 scans, interrupt analytics
processing and delivery, resume them, and compare published rankings with the reference sequence.

**Acceptance Scenarios**:

1. **Given** catalog products and known stock levels, **When** catalog and low-stock views are
   requested, **Then** names, prices, thresholds, and current physical stock match the owned data;
   a reservation alone is not reported as a physical stock decrement.
2. **Given** Analytics or event delivery is temporarily unavailable, **When** valid checkout
   actions succeed, **Then** scans remain durable and do not wait for ranking publication.
3. **Given** delivery resumes after an interruption, **When** pending scans are processed,
   **Then** each accepted scan contributes once to each window containing it, including scans
   beyond the first delivery batch; duplicate deliveries do not increase counts.
4. **Given** 1,500 accepted scans in a known order with default window settings, **When** the
   corresponding publication completes, **Then** the ranking at scan 1,500 reflects scans
   501 through 1,500, with deterministic SKU ordering for equal counts.
5. **Given** a ranking update is in progress, **When** popular items are requested, **Then** the
   result is a complete old or new snapshot, never a mixture of unfinished updates.
6. **Given** no accepted scans, **When** popular items are requested, **Then** a valid empty
   result is returned; accepted scans do not require a completed purchase to count as popular.

---

### User Story 5 - Verify Performance Improvements With Comparable Evidence (Priority: P3)

An instructor or operator can compare the previous service-based system with the microservice
redesign and determine whether checkout becomes faster without sacrificing correctness.

**Why this priority**: Extra deployment boundaries are not evidence of improved speed.

**Independent Test**: Run the unchanged measuring client against the recorded baseline and the
redesign under matching provisioning and workload settings, then compare reports and reconciliation.

**Acceptance Scenarios**:

1. **Given** the pre-migration implementation, **When** baseline workloads run, **Then** reports
   record configuration, operation outcomes, mean/p95/p99 latency, throughput, and bottleneck
   observations before architectural changes are evaluated.
2. **Given** baseline and redesign reports, **When** the comparison is reviewed, **Then** workload,
   catalog, stock, hardware, resource budgets, and deployment differences are visible; failed or
   unconfirmed requests are not omitted to make latency appear lower.
3. **Given** a measured improvement, **When** the reviewer follows the evidence, **Then** it meets
   the stated performance target while contract, inventory, and recovery checks still pass.

### Edge Cases

- Repeated SKU scans exceed stock: fulfill only available units and retain the exact shortfall
  in the existing basket without adding shortage fields, objects, or extra messages.
- A scan races completion: include it only if accepted before basket freeze; otherwise reject
  immediately with `409 TRANSACTION_BUSY`. Do not queue it or record it as an accepted scan.
- Duplicate completion requests arrive simultaneously: only one unresolved attempt owns the
  basket; later legitimate attempts can purchase previously unfulfilled quantities.
- A timeout follows a successful reservation or stock commit: resolve the original attempt
  before compensation or a new attempt; timeout alone is not proof of failure. Return
  `503 SERVICE_UNAVAILABLE` in the existing error format if completion cannot be confirmed
  within the configured request deadline, without discarding durable recovery progress.
- Cancellation races stock commit: exactly one outcome wins; committed purchases are finalized,
  while cancelled reservations cannot later commit.
- A process crashes between a business update and event delivery: accepted work remains durable.
- A consumer crashes before acknowledging processed work: redelivery causes no duplicate effect.
- Events arrive duplicated or out of order: preserve the documented scan order and window
  boundaries across replay; rejected scans never contribute.
- Analytics or delivery remains stopped beyond a publication boundary: checkout remains usable,
  backlog remains visible, and newer scans are not starved behind already processed records.
- Catalog data changes after scanning: captured purchase prices do not change retrospectively.
- A product lookup or optional local copy is unavailable/stale: apply the documented validity
  and freshness policy rather than accept an unknown product or invent a price.
- A basket has no fulfillable units: return `409 INSUFFICIENT_STOCK` in the existing error format,
  create no receipt, and leave physical stock and the open basket unchanged.
- A service restarts with open baskets or reservations: durable state survives and recovery can
  account for each accepted scan, attempt, reservation, receipt, and stock movement.
- An incompatible internal contract is deployed: compatibility verification rejects it before
  it breaks supported consumers.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide independently deployable Catalog, Checkout, Inventory,
  and Analytics services, with the existing single public entry point.
- **FR-002**: Each service MUST own a private, independently provisionable data store, access
  credentials, and data-lifecycle changes; only its owner may access its persistence structures.
- **FR-003**: Services MUST exchange information only through documented, versioned service
  contracts or durable messages. Cross-store reads, writes, joins, integrity constraints,
  procedures, and access to another service's outgoing records are prohibited.
- **FR-004**: Catalog MUST own product identity, validity, name, and authoritative price;
  Checkout MUST own transactions, baskets, captured prices, receipts, and completion progress;
  Inventory MUST own stock, reservations, fulfillment, and movements; Analytics MUST own scan
  consumption, windows, rankings, and published results.
- **FR-005**: The gateway MUST perform transport/routing and contract translation only; it MUST
  NOT own domain persistence, business decisions, or completion coordination.
- **FR-006**: All seven existing public operations MUST retain approved request/response formats,
  status codes, error identifiers, and synchronous outcomes. Internal progress MUST NOT be
  exposed as an unapproved public transaction status or successful pending purchase.
  This clarification authorizes 200 per-attempt receipts for partial purchases,
  `409 INSUFFICIENT_STOCK` for zero fulfillment, `409 TRANSACTION_BUSY` for conflicting scans,
  and `503 SERVICE_UNAVAILABLE` for unconfirmed completion timeouts, all using existing body
  structures. FR-034 requires documenting these changes before implementation.
- **FR-007**: Starting a valid transaction MUST durably create an open basket; each successful
  scan MUST durably add exactly one unit and capture its purchase price before acknowledgment.
- **FR-008**: A valid scan MUST NOT be rejected solely for insufficient stock and MUST NOT
  reserve or decrement inventory. Missing or closed transactions and invalid SKUs MUST follow
  existing public error behavior.
- **FR-009**: Checkout MUST obtain valid product information through a supported service
  boundary. If it retains a local copy, its freshness, versioning, rebuild, and unavailable-data
  behavior MUST be documented before implementation. Calculations MUST preserve exact cents.
- **FR-010**: Checkout MUST coordinate completion across private stores using durable progress
  and recoverable local changes; a shared transaction across service stores is prohibited.
- **FR-011**: Each completion attempt MUST have a stable unique identity distinct from the
  transaction identity, an immutable requested basket snapshot, and at most one unresolved
  attempt per transaction. Concurrent scans/completions MUST NOT mutate that snapshot.
  A scan targeting an unresolved completion MUST immediately return `409 TRANSACTION_BUSY`
  without waiting for workflow resolution, changing the basket, or emitting a scan event.
  A completion arriving while an attempt remains unresolved MUST join or resume that attempt,
  within the same bounded response policy, rather than create another attempt.
- **FR-012**: Inventory MUST reserve only available quantities during completion, prevent
  negative stock and over-reservation under concurrency, and decrement physical stock only
  when a reservation is committed. Releasing a reservation MUST NOT decrement physical stock.
- **FR-013**: Completion MUST sell available quantities independently and retain each unavailable
  quantity in the existing basket for later purchase. A partial purchase MUST use the existing
  receipt structure for purchased quantities and MUST NOT add shortage fields, response objects,
  or extra messages. The transaction MUST remain open until its remaining basket is empty.
  A zero-fulfillment attempt MUST return `409 INSUFFICIENT_STOCK` in the existing error format,
  create no receipt, change no physical stock, and leave the basket open and unchanged.
  Remaining quantities are represented by the basket and the difference from purchased receipt
  quantities, not a new shortage payload.
- **FR-014**: Completion MUST reserve stock, durably prepare the corresponding receipt outcome,
  commit that reservation, and finalize purchased receipt lines and the remaining basket.
  A success response MUST describe only confirmed purchases from that completion attempt:
  `lines`, `itemCount`, and `totalAmount` MUST exclude purchases from previous attempts.
  All attempts MUST retain the original `transactionId`, `stationId`, and transaction
  `startedAt`. The receipt's `completedAt` MUST be the finalization time of that purchase
  attempt, even if the overall transaction remains open with unavailable items.
- **FR-015**: Repeated completion commands and recovered attempts MUST NOT duplicate stock
  movements, purchased quantities, or receipt contributions. Later attempts on a partial basket
  MUST NOT resell previously purchased quantities.
  Idempotency of internal commands is scoped to the durable attempt identity. After a partial
  attempt finalizes, a later public completion call starts a new attempt for the remaining basket;
  after the transaction completes, it returns `409 TRANSACTION_NOT_OPEN` without further writes.
- **FR-016**: A lost response or timeout MUST leave the original attempt recoverable; the system
  MUST resolve its outcome before starting conflicting work or deciding to compensate it.
  When Inventory unavailability prevents a confirmed completion within a configured finite
  request timeout, return `503 SERVICE_UNAVAILABLE` in the existing error format. Do not cancel
  the attempt or restore committed stock merely because the response deadline expired.
- **FR-017**: Cancelling an uncommitted attempt MUST release all its reservations. A committed
  inventory outcome MUST instead lead to receipt finalization; timeout alone MUST NOT restore
  sold stock. Cancellation and commit MUST have one unambiguous outcome.
- **FR-018**: Recovery MUST survive service restarts, use bounded retries, expose unresolved
  attempts to operators, and account for abandoned reservations without allowing expired or
  cancelled reservations to commit later. It MUST NOT wait on a remote service while holding
  a local business-data transaction or inventory lock open.
- **FR-019**: Every committed stock movement MUST reference a durable completion attempt. After
  attempts settle, initial stock minus final stock MUST equal purchased receipt quantities for
  every SKU; no unexplained reservation or unresolved attempt may remain after validation.
- **FR-020**: Each accepted scan and its pending analytics notification MUST become durable as
  one local outcome. Delivery failures MUST NOT erase the scan or its pending notification.
- **FR-021**: Accepted scans MUST reach Analytics through durable delivery owned by the sender;
  Analytics MUST NOT inspect Checkout's store. Delivery and consumption MUST resume after restart.
- **FR-022**: Repeated delivery MUST have one business effect. Processing progress and analytics
  effects MUST become durable together before acknowledging the received work.
- **FR-023**: Analytics MUST retain the ordered `ingest -> window -> rank-and-publish` behavior,
  count accepted rather than purchased units, and publish only complete rankings. Default
  windows contain the most recent 1,000 scans and refresh every 500 scans, with the existing
  startup partial-window behavior preserved.
- **FR-024**: Scan identity and canonical ordering MUST remain stable across concurrent arrivals,
  duplicate delivery, restart, and replay. Window boundaries and equal-count rankings MUST be
  reproducible. Delivery MUST advance beyond processed batches without starving newer scans.
- **FR-025**: An Analytics or delivery outage MUST NOT block otherwise valid checkout operations;
  accepted events MUST remain durable and operator-visible until processing resumes. Shutdown
  MUST drain accepted work or preserve it for recovery.
- **FR-026**: Catalog, current low-stock, transaction lookup, and popular-item views MUST preserve
  their existing public meaning and fields, including product names and physical stock counts.
- **FR-027**: Every service MUST support independent configuration, initialization, owned-data
  migration, readiness verification, restart, and graceful shutdown without requiring a
  coordinated release of all services for compatible changes.
- **FR-028**: Store access MUST enforce ownership; credentials MUST come from deployment
  configuration. Shared helpers MUST NOT provide access to another domain's persistence or
  embed shared mutable domain state or cross-domain business decisions.
- **FR-029**: Internal contracts MUST document message/operation identities, versions, ordering,
  timeouts, duplicate handling, delivery progress, compatibility, and failure/recovery behavior.
  Dependency failure MUST NOT be disguised as a missing product or a confirmed purchase.
- **FR-030**: Before migration, the unchanged client MUST record a reproducible service-based
  baseline and observations sufficient to distinguish service, data-access, contention,
  connection-wait, retry, and resource-capacity bottlenecks.
- **FR-031**: Baseline and redesigned workloads MUST record timestamped normal/stress reports,
  operation counts, errors, mean/p95/p99 latency, throughput, resource/topology settings, and data
  provisioning. Performance comparisons MUST identify material differences and preserve failures.
- **FR-032**: Validation MUST include concurrent stock competition, partial retries, interruption
  at each completion boundary, lost responses, duplicate delivery, Analytics/delivery outages,
  and processing beyond the first event batch, followed by reconciliation.
- **FR-033**: Repository documentation MUST identify deployments, owners, permissions, service
  interactions, completion recovery, analytics flow, baseline comparisons, and reproducible
  validation procedures. Historical artifacts MUST NOT be presented as new validation evidence.
- **FR-034**: Before implementation, planning MUST reconcile the current contract/documentation
  gaps listed under Assumptions. Unsupported response fields, error statuses, or behavioral
  changes MUST follow the constitution's amendment process rather than be silently introduced.

### Key Entities

- **Catalog item**: Product identity, name, valid-for-sale state, and authoritative price;
  owned by Catalog.
- **Checkout transaction and basket line**: Station, public lifecycle state, scanned quantities,
  captured prices, and remaining quantities; owned by Checkout.
- **Completion attempt**: One immutable basket request, unique attempt identity, confirmed
  outcomes, recovery progress, and terminal result; owned by Checkout and referenced by Inventory.
- **Inventory position**: Physical stock and quantity reserved for unresolved attempts for one
  SKU; owned by Inventory.
- **Reservation and stock movement**: Attempt-specific allocated quantity, committed/released
  outcome, and auditable physical stock change; owned by Inventory.
- **Receipt and purchased line**: Confirmed purchased quantities and captured prices for one
  successful attempt, owned by Checkout. Receipts share the transaction ID but each contains
  only that attempt's purchases and finalization time. Reconciliation sums distinct successful
  attempts once, not repeated responses; no new public receipt or attempt identifier is added.
- **Accepted scan and pending notification**: Durable evidence that one scan succeeded, its
  identity and order, and delivery progress; owned by Checkout.
- **Analytics processing record**: Received scan identity, canonical order, and processed
  contribution supporting safe redelivery; owned by Analytics.
- **Popularity snapshot**: Complete ranking, window boundaries, and publication time; owned
  by Analytics. One scan can contribute to multiple overlapping windows once per window.
- **Workload evidence**: Versioned baseline/redesign reports, provisioning, measured outcomes,
  recovery observations, and reconciliation results.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All seven existing public operations pass the approved compatibility checks;
  zero successful responses describe unfinished purchases or unapproved public lifecycle states.
  Every scan submitted while its completion remains unresolved immediately receives
  `409 TRANSACTION_BUSY`, is not queued for later acceptance, and creates zero basket units or
  accepted-scan events. Every successful partial purchase returns 200 with a per-attempt receipt.
- **SC-002**: All four services can be started, verified, and redeployed independently with
  their owned data. All tested attempts to access another owner's store are denied.
- **SC-003**: In controlled concurrent and partial-fulfillment tests, 100% of available purchased
  quantities are reflected once in stock and receipts; unavailable quantities remain purchasable
  in the basket, and zero SKU quantities become negative. Across successive partial purchases,
  receipts retain the same transaction ID, and each receipt's lines, count, and total include
  only the quantities purchased in its own attempt.
- **SC-004**: For every tested completion interruption and duplicate command, recovery produces
  exactly one committed purchase or one released attempt. Within 60 seconds of dependencies
  becoming healthy in the controlled test, no unexplained attempt or reservation remains.
  During an Inventory outage, each timed-out request returns `503 SERVICE_UNAVAILABLE` within
  the configured finite timeout; its durable progress remains recoverable after the response.
- **SC-005**: After each normal, stress, and injected-failure run settles, reconciliation passes
  for 100% of SKUs: starting stock minus ending stock equals purchased receipt quantities, with
  no untraceable stock movement or duplicate receipt contribution.
- **SC-006**: For a known 1,500-scan sequence, the publication ending at scan 1,500 exactly matches
  the expected ranking over scans 501-1,500. After replay and duplicate delivery, the same input
  order produces identical window boundaries, counts, and ranks.
- **SC-007**: In an outage/restart test with at least 10,000 accepted scans and continued later
  arrivals, zero scans are lost or counted twice per containing window; all pending accepted
  scans are eventually processed after recovery, including those beyond the oldest batch.
- **SC-008**: With adequate provisioned stock, the 10-station/60-second normal workload completes
  with zero operation errors. The 200-station/180-second stress workload records all successes
  and failures, including all requested latency percentiles and final reconciliation.
- **SC-009**: Across three comparable baseline runs and three redesign runs, median stress
  scan p95 latency is at least 10% lower than baseline, successful scan throughput is no lower,
  and median start/complete p95 latency does not regress. An unmet target MUST be reported as
  unmet rather than inferred from deployment architecture or hidden by excluded failures.
- **SC-010**: During a controlled 30-second Analytics/delivery interruption, otherwise healthy
  checkout operations continue to return correct results, and all accepted scan contributions
  become recoverable without shopper retries.
- **SC-011**: A reviewer can find deployment/ownership boundaries, completion recovery rules,
  analytics ordering, baseline/redesign reports, and reconciliation evidence from one repository
  entry point in under five minutes.

## Assumptions

- This invocation updates `specs/004-domain-services/spec.md` in place and retains its original
  creation date and active feature selection. Git branch names and feature folder names are
  independent; the current working branch is `microservice`.
- Constitution v3.0.0 takes precedence over the prior centralized-database/no-direct-call design.
  This feature migrates the existing database-coordinated services, not the earlier in-memory
  layered server. The four required business services and gateway are retained.
- The public compatibility source is [the OpenAPI contract](../../spec/self-checkout-openapi.yaml),
  with the load client and mock server unmodified. Payment is simulated to succeed; payment
  processing, new public endpoints, customer authentication, refunds, dynamic catalog
  administration, and a public restocking workflow are outside this feature.
- Baseline and redesign use matching catalog and stock seeds with sufficient stock for measured
  runs. Normal is 10 stations for 60 seconds; stress is 200 stations for 180 seconds, matching
  this feature's existing evidence. Stock-shortage behavior is tested separately.
- The 10% scan-p95 improvement and 60-second controlled recovery bound are explicit initial
  acceptance targets for this redesign, not measured claims. Outage time is excluded from the
  recovery bound. Repeated performance runs use reset state and comparable resource budgets.
- Physical provisioning, internal contract shapes, delivery technology, product lookup strategy,
  retry budgets, and scan-order assignment are planning decisions governed by the constitution;
  this specification does not select an implementation framework or message product.
- Partial-fulfillment representation is clarified: unavailable quantities remain in the existing
  basket; purchased quantities use the existing receipt, without new shortage fields, objects,
  or extra messages. The user's clarification governs over any interpretation of constitution
  IV that would require an explicit new customer-facing shortage payload. Private workflow and
  reservation records required for reliable coordination are unchanged by this response rule.
- Contract alignment remains a pre-implementation dependency: the completion description says
  it finalizes the transaction, whereas a partial purchase leaves it open. Receipt scope is
  clarified as per-attempt, under the original transaction ID; `startedAt` remains the original
  transaction start and `completedAt` records that attempt's finalized purchase. Planning MUST
  document these clarified semantics and any required contract-description amendment without
  introducing new response structures or silently changing completed-state meaning.
- The all-unavailable completion mapping is clarified as `409 INSUFFICIENT_STOCK` with the
  existing error format and no receipt, stock change, or basket closure. Existing older guidance
  that cancels a transaction on this error does not apply. Planning MUST document this behavior
  in the completion conflict description without adding a new response structure.
- Inventory-dependent completion timeout behavior is clarified as `503 SERVICE_UNAVAILABLE`
  using the existing error format, with durable recovery retained and no implied cancellation.
  Planning MUST specify a configurable finite timeout and document the additional 503 outcome
  through the required contract amendment before implementation. No new response structure,
  public pending endpoint, or required client idempotency header is introduced.
- Conflicting scans immediately return `409 TRANSACTION_BUSY` using the existing error format;
  they do not wait, mutate the basket, or count toward analytics. The public transaction may
  remain OPEN while its internal completion is busy; no new public lifecycle status is added.
  Concurrent completion calls join/resume the unresolved attempt under the bounded timeout
  policy. Planning MUST document the busy error and timeout mapping in the public contract.
- Public completion retries have no client-provided operation ID today. The internal attempt
  identity MUST therefore survive recovery and duplicate calls without requiring a client change;
  tests MUST distinguish duplicates during an unresolved attempt from later legitimate partial
  completion attempts using the documented attempt lifecycle.
  Once a partial attempt has finalized, identical public requests cannot distinguish a lost
  response retry from an intentional next purchase. Such a call operates on the remaining basket
  as a new attempt; it never resells fulfilled quantities. Exactly-once replay of an arbitrary
  finalized partial response is not promised without a public request identity. Planning MUST
  preserve this boundary rather than claim stronger end-to-end idempotency.
- `architecture-characteristics.md` and older feature/error-code documents describe all-or-nothing
  completion or scan-time claims. Those rules are superseded here by partial completion and
  completion-time reservations; the user's existing edits are preserved. Their applicable
  guidance MUST be reconciled before implementation.
- Planning MUST produce this feature's `plan.md`, `research.md`, `data-model.md`, `contracts/`,
  and `quickstart.md` for the private-store design with documented recovery/contract mappings,
  and `tasks.md` MUST be generated afterwards. Planning MUST replace the no-direct-call guard
  with private-store and supported-contract checks. Application, deployment and canonical public
  YAML remain unchanged during specification and planning; synchronize any public contract
  amendment recorded by planning before code.
