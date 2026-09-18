# Phase 1 Data Model: Self-Checkout System API — Monolith Week

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**:
[research.md](./research.md) | **Date**: 2026-09-17

Entities as the monolith holds them in memory. Requirement references point to
[spec.md](./spec.md) (FR-001–FR-032); design rationale lives in [research.md](./research.md).

---

## Entity: CatalogItem

Immutable once seeded (FR-002).

| Field | Type | Notes |
|---|---|---|
| `sku` | `String` | Identity. Format `SKU-%06d` from 1 (R11) |
| `name` | `String` | Display name, derived deterministically from the index |
| `priceCents` | `long` | Minor units. Serialized as a 2-decimal JSON number (R7) |

**Validation**

- `sku` unique across the catalog; non-blank.
- `priceCents >= 0`.
- The same configuration MUST yield a byte-identical catalog on every boot and in every week
  (R11) — SC-008's cross-week popularity check depends on it.

**Relationships**: one `CatalogItem` ↔ one `SkuInventory`, keyed by `sku`.

---

## Entity: SkuInventory

The week's critical mutable state. One instance per SKU, and the only place stock changes.

| Field | Type | Notes |
|---|---|---|
| `sku` | `String` | Identity |
| `stock` | `int` | Physical units on hand. Moves **only** on successful completion (FR-015) |
| `reserved` | `int` | Units currently claimed by open transactions (FR-017) |
| `lowStockSince` | `Instant \| null` | When stock last fell strictly below the configured default threshold (R10) |

**Core invariant**, holding at every observable moment (R4):

```text
stock >= reserved >= 0
```

Every claim is therefore backed by a physical unit — which is what makes a transaction whose
units are all claimed guaranteed to complete.

**Validation and rules**

- A scan claims one unit iff `stock - reserved > 0`, incrementing `reserved` (FR-017). When it
  cannot, the scan still succeeds and the unit is recorded unclaimed (FR-007).
- Claims are released (`reserved` decremented) on successful completion **and** on
  cancellation (FR-017).
- Claim grant and per-SKU mutation are atomic (FR-016); the multi-SKU decrement at completion
  is applied as one step under sorted-order locks (FR-010, R5).
- `stock` MUST never go negative (FR-015).
- `lowStockSince` is stamped when a decrement takes stock strictly below the default threshold
  and cleared if stock rises to or above it. Equality with the threshold is **not** a crossing
  (FR-019).
- Only the `inventory` package may mutate this entity.

---

## Entity: InventoryClaim *(internal — never exposed)*

Modeled as counts rather than objects: `SkuInventory.reserved` aggregates all open claims, and
each transaction records how many of its own scanned units are claimed per SKU. No claim
identity is needed — claims are fungible, since any claimed unit of a SKU is interchangeable
with any other.

**Lifecycle**: taken by a scan that finds an unclaimed unit → held while the transaction stays
open → released when the transaction completes or is cancelled (FR-017, R6). No expiry,
timeout, or sweeper.

**Exposure**: none. No operation reads, queries, or manipulates claims, and no payload reports
them (FR-031). Their only externally visible consequence is the `INSUFFICIENT_STOCK` conflict.

---

## Entity: Transaction

| Field | Type | Notes |
|---|---|---|
| `transactionId` | `String` | Identity. Server-assigned, unique (FR-003) |
| `stationId` | `String` | Required; absent → 400 (FR-004) |
| `status` | `OPEN \| COMPLETED \| CANCELLED` | All three reachable — see transitions below |
| `scannedQty` | `Map<String, int>` | Units recorded per SKU, claimed or not |
| `claimedQty` | `Map<String, int>` | Of those, how many hold a claim |
| `itemCount` | `int` | Total units; equals the sum of `scannedQty` |
| `runningTotalCents` | `long` | Sum of scanned unit prices (FR-030) |
| `startedAt` | `Instant` | Set at creation |
| `completedAt` | `Instant \| null` | Set only on successful completion |

**Validation and rules**

- `claimedQty[sku] <= scannedQty[sku]` for every SKU. Any shortfall is permanent: claims are
  taken only at scan time, so an unclaimed unit never becomes claimed (R6).
- **Completion feasibility is transaction-local**: `claimedQty[sku] == scannedQty[sku]` for
  every SKU. No shared inventory state is consulted (FR-017, R5).
- `itemCount` and `runningTotalCents` are maintained on every accepted scan — FR-006 returns
  them per scan.
- Scans are accepted only while `status == OPEN`; otherwise 409 (FR-009).
- Completion requires `status == OPEN` and `itemCount > 0`; otherwise 409 (FR-012).

**State transitions**

```text
                    ┌─────────────────────────────────────────┐
                    │ scan (FR-005) — stays OPEN; claims one  │
                    │ unit if available (FR-017), else        │
                    ▼ records the unit unclaimed (FR-007)     │
   (create) ──> OPEN ────────────────────────────────────────┘
                 │  │
                 │  ├─ complete, every unit claimed ────────> COMPLETED
                 │  │     stock decremented, claims released,
                 │  │     receipt issued (FR-010, FR-011)
                 │  │
                 │  └─ complete, any unit unclaimed ────────> CANCELLED
                 │        409 INSUFFICIENT_STOCK; no receipt,
                 │        no stock change, claims released (FR-013, R6)
                 │
                 └──── customer abandons ──────────────────> CANCELLED
                          claims released, no stock change
```

An insufficient-stock refusal is **terminal**, not a retryable pause: because claims are only
taken at scan time and no operation removes a unit from a basket, such a transaction can never
complete, so it is cancelled and its claims returned to the pool (R6). Both terminal states are
excluded from reconciliation by FR-018.

---

## Entity: Receipt

Issued only on successful completion (FR-011). Derived, never stored as mutable state.

| Field | Type | Notes |
|---|---|---|
| `transactionId`, `stationId` | `String` | Copied from the transaction |
| `itemCount` | `int` | Total units |
| `totalAmountCents` | `long` | Equals `runningTotalCents` exactly (FR-030, SC-006) |
| `startedAt`, `completedAt` | `Instant` | |
| `lines` | `List<ReceiptLine>` | One per **distinct** SKU |

**ReceiptLine**: `sku`, `name`, `unitPriceCents`, `quantity`.

**Validation**

- One line per distinct SKU — three scans of one SKU aggregate into `quantity: 3` (US1 §4),
  not three lines.
- `sum(line.unitPriceCents * line.quantity) == totalAmountCents`, exactly.
- `sum(line.quantity) == itemCount`.
- Each `line.quantity` equals the stock decrement applied to that SKU — the equality SC-004's
  reconciliation audits.

---

## Entity: LowStockAlert

Computed per query (FR-019); never stored.

| Field | Type | Notes |
|---|---|---|
| `sku`, `name` | `String` | |
| `currentStock` | `int` | **Physical** stock. Claimed-but-unsold units count as on hand (FR-021) |
| `threshold` | `int` | The threshold in force for this query |
| `triggeredAt` | `Instant` | `lowStockSince`, falling back to report generation time for override queries (R10) |

**Rules**

- Included iff `stock < threshold` — **strictly** below. A SKU whose stock equals the threshold
  is not reported (FR-019, US3 §4).
- Because `stock >= 0` always, `threshold = 0` matches nothing and yields an empty list (R10).
- A query-supplied threshold applies to that query only and never mutates the configured
  default (FR-020).
- Claims are ignored entirely — a claim is not a stock movement (US3 §5).
- Nothing below threshold → empty list, not an error (US3 §3).

---

## Entity: ScanEvent and PopularityWindow

**ScanEvent** is not retained as an object. Each accepted scan takes the next value from a
global `AtomicLong` — that increment is the linearization point at which the scan is accepted,
so sequence numbers are unique and strictly increasing, and sequence order rather than
wall-clock order defines analytics ordering (FR-022). The scan writes its SKU into a ring
buffer slot `(seq - 1) % windowSize` (R8).

**PopularityWindow** is an immutable snapshot, republished only at boundaries:

| Field | Type | Notes |
|---|---|---|
| `windowSize`, `slideInterval` | `int` | Config; defaults 1000 / 500. Must satisfy `windowSize >= 1` and `1 <= slideInterval <= windowSize` (FR-027) |
| `windowStart`, `windowEnd` | `long` | `windowEnd` = boundary position; `windowStart` = `windowEnd - windowSize + 1` (FR-024) |
| `computedAt` | `Instant` | |
| `items` | `List<PopularItem>` | `sku`, `name`, `scanCount`, `rank` |

**Rules**

- Boundaries fall at sequence position `windowSize`, then every `slideInterval` thereafter —
  1000, 1500, 2000… for the defaults (FR-023).
- A query at a position between two boundaries returns the snapshot computed at the most
  recently completed boundary (FR-023, US4 §4), so two queries between boundaries are
  identical.
- Before the first boundary, the published snapshot is empty with `windowStart = windowEnd = 0`
  (FR-024).
- Ranked by `scanCount` descending, `rank` from 1; ties broken stably by SKU (FR-025).
- `limit >= 1`, default 10, caps the returned list (FR-025, FR-027).
- A scan counts at scan time regardless of whether its transaction completes, is cancelled, or
  is refused (FR-026).

---

## Cross-entity invariants

The auditable properties, and the reason R14's correctness tests exist.

1. **Claim backing** — for every SKU, `stock >= reserved >= 0` (R4).
2. **Reconciliation** — for every SKU, `initialStock - currentStock` equals the total quantity
   of that SKU across all `COMPLETED` transactions (FR-018, SC-004). `CANCELLED` and still-open
   transactions contribute to neither side.
3. **Non-negativity** — no SKU's `stock` is ever below zero (FR-015).
4. **Monetary exactness** — every receipt total equals the sum of its lines, and every
   transaction's running total equals the sum of its scanned unit prices (FR-030).
5. **Claim conservation** — for every SKU, `reserved` equals the sum of `claimedQty[sku]` across
   all `OPEN` transactions. Completion and cancellation both release exactly what they held, so
   no claim leaks and none is released twice.
