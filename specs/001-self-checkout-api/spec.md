# Feature Specification: Self-Checkout System API

**Feature Branch**: `001-self-checkout-api`

**Created**: 2026-09-17

**Status**: Draft

**Input**: The shared API contract at `spec/self-checkout-openapi.yaml` — seven operations
that every weekly implementation must satisfy regardless of its internal architecture.

**Normative source**: `spec/self-checkout-openapi.yaml` (OpenAPI 3.0.3, version 1.1.0) is
authoritative. This document states the behavior behind that contract — the rules a schema
cannot express — and is derived from it operation by operation. Where the two disagree, the
contract wins and this document is corrected.

## Contract surface

Every requirement below traces to one of these operations.

| Operation | Purpose | Success | Documented failures |
|---|---|---|---|
| `GET /items` | Full catalog; client reads it once at startup | 200 | — |
| `POST /transactions` | Start a transaction at a station | 201 | 400 |
| `POST /transactions/{id}/items` | Scan one unit into the basket | 200 | 404, 409 |
| `POST /transactions/{id}/complete` | Pay, decrement stock, return a receipt | 200 | 404, 409 |
| `GET /transactions/{id}` | Inspect a transaction (debugging only) | 200 | 404 |
| `GET /inventory/low-stock` | Current low-stock alerts | 200 | — |
| `GET /analytics/popular-items` | Most-scanned items in the current window | 200 | — |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Complete a purchase at a station (Priority: P1)

A customer arrives at a station holding their items. The station reads the catalog, opens a
transaction, registers each unit as it is scanned, shows a running total, and on payment
issues a receipt and adjusts store stock to match what left the building.

**Why this priority**: the system's entire purpose. Without it there is no stock movement and
nothing for the other stories to report on.

**Independent Test**: fetch the catalog, open a transaction, scan several known SKUs,
complete it, then confirm the receipt lines and total match the scans and each SKU's stock
fell by exactly the units scanned.

**Acceptance Scenarios**:

1. **Given** a seeded catalog, **When** the catalog is requested, **Then** every item is
   returned with its SKU, name, and unit price.
2. **Given** a station identifier, **When** a transaction is opened, **Then** it is created
   with a unique identifier, status open, zero units, and a zero running total.
3. **Given** an open transaction, **When** one unit of a known SKU is scanned, **Then** the
   unit count rises by exactly one and the running total by exactly that item's unit price.
4. **Given** the same SKU scanned three times, **When** the transaction completes, **Then**
   the receipt carries that SKU once with quantity three, and the total equals the sum of all
   scanned unit prices.
5. **Given** a completed transaction, **When** stock is inspected, **Then** each SKU fell by
   exactly the quantity shown on the receipt.
6. **Given** an item with no unclaimed inventory left, **When** it is scanned, **Then** the
   scan succeeds and the unit is recorded — the customer is already holding it — even though
   no inventory is claimed for it.

---

### User Story 2 - Keep inventory accurate under concurrency (Priority: P2)

Many stations complete transactions at once against one shared inventory. Counts must still
reconcile afterwards: nothing sold twice, nothing lost, nothing negative. When demand truly
exceeds what is on hand, the store refuses the checkout rather than overselling.

**Why this priority**: the correctness property that separates a working implementation from
one that merely passes functional tests. Graded independently of any performance number.

**Independent Test**: run many concurrent stations to completion against known starting
stock, then reconcile per SKU: starting minus ending stock must equal the quantity across
successfully completed transactions, with no SKU negative.

**Acceptance Scenarios**:

1. **Given** many stations completing concurrently, **When** the run ends, **Then** for every
   SKU, starting minus ending stock equals the total quantity across successfully completed
   transactions.
2. **Given** one unit left and two transactions that have both scanned it, **When** both
   attempt completion, **Then** the transaction holding the claim completes and receives a
   receipt, the other is refused for insufficient stock, the unit is deducted once, and stock
   settles at zero.
3. **Given** two units left and two transactions that have each scanned one, **When** both
   complete, **Then** both succeed and stock settles at zero.
4. **Given** a completion refused for insufficient stock, **When** the system is inspected,
   **Then** no receipt exists, the transaction is not marked completed, and no stock has
   changed.
5. **Given** an already-completed transaction, **When** completion is requested again,
   **Then** it is refused and stock is not decremented twice.
6. **Given** a cancelled transaction, **When** inventory is inspected, **Then** any units it
   had claimed are available to other transactions again, and no stock was decremented on its
   behalf.

---

### User Story 3 - See which items are running low (Priority: P3)

An operator wants to know which items have dropped below a restocking threshold, so shelves
are refilled before items run out.

**Why this priority**: real operational value, but checkout works without it. It reads state
Story 1 produces.

**Independent Test**: complete enough transactions to push known SKUs below the threshold,
query low stock, and confirm exactly those SKUs are reported with their current counts.

**Acceptance Scenarios**:

1. **Given** items below the configured threshold, **When** low stock is queried, **Then**
   each is reported with its SKU, name, current stock, the threshold applied, and when it
   crossed.
2. **Given** a query supplying its own threshold, **When** low stock is queried, **Then** the
   result reflects that threshold and the configured default is unchanged for later queries.
3. **Given** nothing below the threshold, **When** low stock is queried, **Then** an empty
   alert list is returned, not an error.
4. **Given** a SKU whose stock is exactly equal to the threshold, **When** low stock is
   queried, **Then** it is **not** reported — the comparison is strictly below.
5. **Given** units claimed by open transactions but not yet sold, **When** low stock is
   queried, **Then** the count reported is the physical stock still on hand.

---

### User Story 4 - See which items are selling right now (Priority: P4)

An operator wants the most-scanned items over the recent past — recent enough to show what is
moving now, not a lifetime total.

**Why this priority**: identical in every week and expected to give the same answer in every
week, which makes it a cross-check that a new architecture did not change behavior. Checkout
does not depend on it.

**Independent Test**: drive a deliberately skewed mix of scans, query popular items, and
confirm the ranking matches the mix and the window bounds match the scans recorded.

**Acceptance Scenarios**:

1. **Given** a scan stream where a few SKUs dominate, **When** popular items are queried,
   **Then** those SKUs are returned ranked by scan count, highest first, from rank 1.
2. **Given** any result, **When** it is inspected, **Then** it reports the window size, slide
   interval, window start and end positions, and computation time — enough to reproduce the
   ranking by hand.
3. **Given** a requested limit of N, **When** popular items are queried, **Then** at most N
   items are returned.
4. **Given** a query made at a scan position between two slide boundaries, **When** popular
   items are queried, **Then** the result is the one computed at the most recently completed
   boundary.
5. **Given** scans in transactions that were never completed, were cancelled, or were refused,
   **When** popular items are queried, **Then** those scans still count — popularity tracks
   scanning, not sales.

---

### User Story 5 - Inspect a transaction while debugging (Priority: P5)

A developer or instructor inspects one transaction's state while building or grading an
implementation.

**Why this priority**: a development and grading aid, outside the measured workload. It must
exist and conform, but no customer journey depends on it.

**Independent Test**: open a transaction, scan into it, fetch it by identifier, and confirm
the status, unit count, and running total match the scans.

**Acceptance Scenarios**:

1. **Given** an open transaction with units scanned, **When** it is fetched by identifier,
   **Then** its station, status, unit count, running total, and start time are returned.
2. **Given** an unknown identifier, **When** a transaction is fetched, **Then** not-found is
   returned rather than an empty or fabricated transaction.

---

### Edge Cases

| Situation | Required behavior |
|---|---|
| Unknown SKU scanned | Refused, not found; basket unchanged |
| Unknown transaction on scan, complete, or fetch | Refused, not found |
| Scan into a transaction that is not open | Refused as conflict; no unit recorded |
| Completing an empty basket | Refused as conflict; no receipt |
| Completing twice | Second attempt refused; stock moves once |
| Scanning a SKU with no unclaimed inventory left | Scan succeeds and the unit is recorded, but no inventory is claimed for it; the shortfall surfaces at completion |
| Two stations racing for the last unit | Both scans are recorded; one obtains the claim; that transaction completes and the other is refused for insufficient stock; stock settles at zero |
| Completion refused for insufficient stock | No receipt, no stock change, transaction not marked completed |
| Transaction cancelled | Its claims return to available inventory; no stock is decremented on its behalf |
| Transaction opened and scanned but never completed | No stock is ever decremented for it; contributes nothing to reconciliation |
| Popular items queried before the first slide boundary | Empty ranking with window start and end both zero — no completed boundary exists yet |
| Popular items queried twice between slide boundaries | Identical results both times |
| Low stock queried with threshold zero | Empty alert list — stock is never negative, so no SKU can be strictly below zero |
| Catalog queried repeatedly during a run | Same items and prices every time |

## Requirements *(mandatory)*

### Functional Requirements

**Catalog — `GET /items`**

- **FR-001**: System MUST return every catalog item in a single response, each with its SKU,
  display name, and unit price.
- **FR-002**: System MUST keep catalog membership and prices stable for a run's entire
  duration, so a client that reads it once at startup stays correct.

**Start a transaction — `POST /transactions`**

- **FR-003**: System MUST create a transaction for a named station, returning a unique
  identifier, the station, status open, a zero unit count, a zero running total, and a start
  timestamp.
- **FR-004**: System MUST refuse a request with no station identifier as a client error.

**Scan an item — `POST /transactions/{id}/items`**

- **FR-005**: System MUST treat each request as exactly one physical unit; two units of a SKU
  require two requests.
- **FR-006**: System MUST return, for each accepted scan, the SKU's name and unit price with
  the transaction's cumulative unit count and running total.
- **FR-007**: System MUST NOT gate a scan on stock availability. A known SKU scanned into an
  open transaction MUST succeed regardless of that SKU's current stock.
- **FR-008**: System MUST refuse a scan naming an unknown transaction or an unknown SKU as
  not found.
- **FR-009**: System MUST refuse a scan into a transaction that is not open as a conflict.

**Complete a transaction — `POST /transactions/{id}/complete`**

- **FR-010**: Completion MUST be all-or-nothing: either every scanned unit is fulfilled and
  stock is decremented for all of them, or nothing changes. Partial fulfillment is
  prohibited.
- **FR-011**: On success, System MUST decrement stock by exactly one per scanned unit, mark
  the transaction completed, and return a receipt carrying the transaction and station
  identifiers, total unit count, total amount, start and completion timestamps, and one line
  per distinct SKU with its name, unit price, and quantity. Payment always succeeds — there is
  no payment failure path.
- **FR-012**: System MUST refuse completion of an unknown transaction as not found, and refuse
  a transaction that is not open, or whose basket is empty, as a conflict.
- **FR-013**: System MUST refuse completion as an insufficient-stock conflict when one or
  more scanned units do not hold a claim that can be fulfilled. On that refusal no receipt is
  issued, no stock changes, and the transaction is not marked completed.

**Inspect a transaction — `GET /transactions/{id}`**

- **FR-014**: System MUST return a transaction's current station, status, unit count, running
  total, and start time by identifier, and MUST report not found for an unknown identifier.

**Inventory correctness**

- **FR-015**: Stock MUST change only on successful completion, and MUST NEVER be negative.
- **FR-016**: The check-then-decrement of a SKU's stock MUST be atomic with respect to
  concurrent completions, so no unit is deducted twice and no deduction is lost.
- **FR-017**: A scan records the requested unit in the transaction regardless of inventory
  availability. If an unclaimed physical unit is available, the system associates one unit of
  available inventory with that transaction. A scan that cannot obtain a claim remains
  recorded in the transaction but does not reserve inventory. Completion succeeds only if all
  scanned units have claims that can be fulfilled atomically. Claims are released when the
  transaction completes or is cancelled. No single physical unit may ever be claimed by two
  transactions at once; how claims are represented and coordinated is internal and
  unconstrained.
- **FR-018**: For every SKU across a run, starting stock minus ending stock MUST equal the
  total quantity of that SKU across successfully completed transactions. Refused, cancelled,
  and never-completed transactions contribute to neither side.

**Low stock — `GET /inventory/low-stock`**

- **FR-019**: System MUST report every SKU whose stock is strictly below the low-stock
  threshold, with its current stock, the threshold applied, when it crossed, and when the
  report was generated. A SKU whose stock equals the threshold MUST NOT be reported. When no
  SKU is below the threshold, the alert list MUST be empty rather than an error.
- **FR-020**: A threshold supplied with the query MUST apply to that query only and MUST NOT
  change the configured default.
- **FR-021**: Reported stock MUST be physical stock on hand. Units claimed by open
  transactions still count as on hand, because a claim is not a stock movement.

**Analytics — `GET /analytics/popular-items`**

- **FR-022**: The scan sequence is assigned at the linearization point at which the scan is
  accepted by the system. Sequence numbers are unique and strictly increasing. The sequence
  order, not wall-clock timestamp order, defines analytics ordering.
- **FR-023**: System MUST rank popularity over a hopping window of the most recent
  `windowSize` scans, recomputed at slide boundaries. The first boundary falls at scan
  sequence position `windowSize`, and further boundaries every `slideInterval` scans
  thereafter — for `windowSize` 1000 and `slideInterval` 500, at positions 1000, 1500, 2000,
  and so on. A query made at position N between two boundaries MUST return the result
  computed at the most recently completed boundary.
- **FR-024**: Every result MUST report the window size, slide interval, window start and end
  sequence positions, and computation time, so the ranking is reproducible from the scan
  stream. `windowEnd` is the boundary position at which the result was computed and
  `windowStart` is `windowEnd - windowSize + 1`. Before the first boundary no completed
  window exists, and the result MUST report an empty ranking with both bounds zero.
- **FR-025**: Items MUST be ranked by scan count descending with ranks from 1, and a
  caller-supplied limit MUST cap how many are returned.
- **FR-026**: A scan MUST count toward popularity when it is scanned, whether or not its
  transaction is ever completed, cancelled, or refused.
- **FR-027**: Configuration and query parameters MUST satisfy `windowSize >= 1`,
  `1 <= slideInterval <= windowSize`, and `limit >= 1`.

**Cross-cutting**

- **FR-028**: Every operation MUST be synchronous request/response. Internal use of events,
  queues, brokers, or orchestration engines is permitted and MUST NOT be observable at the
  boundary.
- **FR-029**: Every refusal MUST carry a machine-readable error code and a human-readable
  message. The insufficient-stock code MUST be distinct and stable, so a caller can tell it
  apart from the other conflict reasons.
- **FR-030**: Monetary amounts MUST be exact to the cent: running totals and receipt totals
  MUST equal the sum of the unit prices of the units scanned.
- **FR-031**: System MUST expose no operation, parameter, header, or field beyond the
  contract. Internal mechanisms — including however claims are tracked under FR-017 — MUST
  NOT be queryable or reportable.
- **FR-032**: Externally visible behavior MUST be identical across every weekly
  implementation. Only internal structure varies.

### Key Entities

- **Catalog Item**: a sellable product — SKU, display name, unit price. Fixed for a run.
- **Stock Level**: physical units on hand for one SKU. Falls only on successful completion;
  never negative. Unaffected by scanning and by claims.
- **Inventory Claim** *(internal)*: an association between one open transaction and one unit
  of available inventory, taken at scan time when a unit is unclaimed, and released when the
  transaction completes or is cancelled. Never exposed through the API.
- **Transaction**: one checkout session at one station — identifier, station, status (open,
  completed, cancelled), units scanned, running total, start time.
- **Scanned Unit**: one physical unit recorded into a transaction, whether or not a claim was
  obtained for it. The atom of stock movement and popularity counting, carrying its position
  in the scan sequence.
- **Receipt**: issued on successful completion; aggregates scanned units into one line per
  SKU with a quantity, plus total amount and start and completion times.
- **Low-Stock Alert**: one SKU whose stock is strictly below a threshold, with its physical
  stock and crossing time.
- **Popularity Window**: a ranking of SKUs by scan count over a bounded span of the scan
  sequence, with that span's start and end positions and its computation time.

## Success Criteria *(mandatory)*

- **SC-001**: A station can complete a full purchase — read catalog, open, scan, pay, receive
  a receipt — with zero failed operations.
- **SC-002**: At the default concurrency and duration used for submissions, against stock
  provisioned to cover the run, every start, scan, and completion succeeds: zero errors, and
  in particular zero insufficient-stock conflicts.
- **SC-003**: Under a stress run at substantially higher concurrency and longer duration, the
  run completes and produces a full report; any non-zero error rate is reported and explained
  rather than silently tolerated.
- **SC-004**: After both runs, reconciliation passes for 100% of SKUs: starting minus ending
  stock equals the quantity across successfully completed transactions, and no SKU is
  negative.
- **SC-005**: Where demand for a SKU deliberately exceeds its stock, exactly one transaction
  completes per available unit, every further transaction holding that SKU is refused for
  insufficient stock, final stock is zero, and no refused transaction leaves a receipt or a
  stock change behind.
- **SC-006**: Receipt totals equal the sum of scanned unit prices, to the cent, for 100% of
  completed transactions.
- **SC-007**: Every operation returns its documented outcome for success and for each
  documented failure — including the insufficient-stock conflict: 100% conformance, verified
  before any performance number is recorded.
- **SC-008**: The top-10 popularity ranking for the same workload agrees across
  implementations; any disagreement is treated as a defect and explained.
- **SC-009**: Each run report states mean, p50, p95, p99, and maximum latency plus error rate
  per measured operation, and overall transaction and item throughput.
- **SC-010**: An operator can identify every item needing restock in one query, and every
  item reported is genuinely below the threshold in force — no false alerts, and no SKU
  sitting exactly at the threshold.

## Assumptions

**Scope boundaries**

- This document covers externally visible behavior shared by all weekly implementations. It
  chooses no architecture, language, storage, or deployment shape — those are the per-week
  decisions the project exists to compare. The mechanism satisfying FR-017 for the current
  week is recorded in [plan.md](./plan.md) and [research.md](./research.md).
- The load client and mock server are measuring instruments, not deliverables, and are out of
  scope for change.
- Cancellation is in scope as a transaction state. A transaction may be cancelled either by
  the customer abandoning it or by the system when its scanned units cannot be fulfilled for
  lack of stock. The contract exposes no cancellation operation, so the state is reached
  internally and observed through transaction inspection.
- The contract defines no retry semantics for a refused completion, so none are specified
  here: a refusal leaves the transaction not completed, and what happens next is outside this
  specification.
- Out of scope entirely: payment failure, refunds, partial fulfillment, removing an item from
  a basket, restocking, authentication and authorization. Stations are trusted.

**Defaults applied**

- Starting stock for measured runs covers the run's scan volume, so insufficient-stock
  conflicts do not fire during runs used for week-to-week comparison and cannot distort the
  latency and error-rate numbers. The insufficient-stock path is still required behavior,
  proven by SC-005's targeted test rather than by the load runs.
- Claims are held while a transaction stays open and released when it completes or is
  cancelled. The contract offers no claim lifetime, so no expiry or timeout mechanism is
  required.
- Window defaults: `windowSize` 1000 with `slideInterval` 500, both configurable within
  FR-027's constraints. The popular-items `limit` defaults to 10.
- The ranking is a snapshot recomputed at slide boundaries, so queries between boundaries
  return identical results. Staleness up to one slide interval is correct, not a defect.
- Low-stock alerts are evaluated against current physical stock at query time; crossing time
  is when the SKU most recently fell below the threshold in force, falling back to report
  generation time when that is unknown for a supplied threshold.
- The low-stock threshold is server configuration, not catalog data.
- No absolute latency target is set. Performance is judged by comparison against earlier
  weeks on comparable hardware and workload, using SC-009's percentiles.
- Concurrency and duration are properties of the measuring instrument, set through the load
  client's flags, not requirements of this contract — hence "default scale" and "stress"
  rather than fixed station counts.
- Ties in the popularity ranking may be broken in any stable, documented way.
