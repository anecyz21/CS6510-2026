# Data Model: Independently Deployable Checkout Microservices

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

Every table below lives in exactly one owner's private MySQL instance. Shared identifiers (SKU,
`transactionId`, `attemptId`, `eventId`) link records logically only: there are **no
cross-database foreign keys, views or procedures** (constitution III). Money is stored as `BIGINT`
cents. Timestamps are `TIMESTAMP(6)` UTC.

## Ownership overview

| Owner (instance) | Entities |
|---|---|
| Catalog (`catalog-db`) | Catalog item, Catalog metadata |
| Checkout (`checkout-db`) | Checkout transaction, Basket line, Completion attempt, Attempt line, Receipt, Receipt line, Accepted-scan outbox, Catalog projection, Projection state |
| Inventory (`inventory-db`) | Inventory position, Reservation, Reservation line, Stock movement, Name projection |
| Analytics (`analytics-db`) | Ingested scan, Ingest state, Popularity snapshot, Snapshot item, Published-snapshot pointer, Name projection |
| Kafka | Topic `checkout.accepted-scans.v1`, owned by Checkout (producer); Analytics owns its consumer group offsets |

---

## Catalog

### Catalog item (`catalog_items`, existing)

| Field | Type | Rules |
|---|---|---|
| `sku` | VARCHAR(64) PK | Non-blank, ≤ 64 chars |
| `name` | VARCHAR(255) | Non-blank, ≤ 255 chars |
| `price_cents` | BIGINT | ≥ 0. Authoritative price |
| `active` | BOOLEAN | Only active items are valid for sale and appear in snapshots |

Seeded with 2,000 items `SKU-000001…SKU-002000` by the existing `V2__seed_catalog.sql`. The view
`active_items` is **dropped**, because nothing outside Catalog may read it.

### Catalog metadata (`catalog_metadata`, new, single row)

| Field | Type | Rules |
|---|---|---|
| `catalog_version` | BIGINT | Incremented by any migration or seed that changes item rows. Starts at 1. |
| `updated_at` | TIMESTAMP(6) | |

The version lets consumers detect staleness (R6). This feature has no runtime catalog writes, so
the version changes only through Catalog's own migrations.

---

## Checkout

### Checkout transaction (`checkout_transactions`, extended)

| Field | Type | Rules |
|---|---|---|
| `transaction_id` | VARCHAR(64) PK | UUID |
| `station_id` | VARCHAR(255) | Non-blank |
| `status` | VARCHAR(16) | `OPEN` \| `COMPLETED` \| `CANCELLED`, the public lifecycle only (FR-006). The current design never sets `CANCELLED`, but the value is kept for contract compatibility. |
| `started_at` | TIMESTAMP(6) | Immutable. Echoed on every receipt |
| `completed_at` | TIMESTAMP(6) NULL | Set when the basket becomes empty in a finalizing attempt |
| `active_attempt_id` | VARCHAR(64) NULL | **Freeze and busy flag.** Non-null ⇔ one unresolved attempt owns the basket (FR-011) |
| `version` | BIGINT | Incremented on every status or active-attempt change (diagnostics) |

Invariants:
- `status = COMPLETED` ⇒ there are no basket lines and `active_attempt_id IS NULL`.
- At most one non-terminal attempt exists per transaction, enforced by `active_attempt_id` under
  a row lock.

### Basket line (`basket_lines`, existing)

| Field | Type | Rules |
|---|---|---|
| `transaction_id`, `sku` | PK | |
| `quantity` | INT | > 0 (zero-quantity rows are deleted) |
| `unit_price_cents` | BIGINT | ≥ 0. Captured at first scan of the SKU from the catalog projection and never re-priced (edge case) |
| `name` | VARCHAR(255) | Captured with the price; used for scan responses and receipt lines |

The open basket **is** the remaining-quantity record. After a partial attempt, each line's
quantity is reduced by the purchased quantity (FR-013).

### Completion attempt (`completion_attempts`, new)

| Field | Type | Rules |
|---|---|---|
| `attempt_id` | VARCHAR(64) PK | UUIDv7, distinct from `transaction_id`. Never exposed publicly |
| `transaction_id` | VARCHAR(64) | Indexed |
| `state` | VARCHAR(16) | See the state machine below |
| `created_at`, `updated_at` | TIMESTAMP(6) | `created_at` drives `cancel-after` |
| `finalized_at` | TIMESTAMP(6) NULL | Becomes the receipt's `completedAt` |
| `step_failures` | INT | Consecutive failed remote steps, reset on progress |
| `next_retry_at` | TIMESTAMP(6) | Recovery eligibility |
| `lease_owner` / `lease_until` | VARCHAR(64) / TIMESTAMP(6) NULL | Single-driver lease (R9) |
| `needs_attention` | BOOLEAN | Set once `step_failures ≥ attention-after`. Shown to operators |
| `last_error` | VARCHAR(512) NULL | Last dependency error (no secrets) |

Index: `(state, next_retry_at)` for the recovery worker.

### Attempt line (`completion_attempt_lines`, new): the immutable snapshot

| Field | Type | Rules |
|---|---|---|
| `attempt_id`, `sku` | PK | |
| `requested_quantity` | INT | > 0. Copied from the basket at freeze; **immutable** |
| `unit_price_cents`, `name` | | Copied from the basket line at freeze |
| `reserved_quantity` | INT NULL | Set at prepare from Inventory's reserve result; 0 ≤ reserved ≤ requested |

Purchased quantity for a `FINALIZED` attempt = `reserved_quantity`.

### Receipt (`receipts`, re-keyed) and Receipt line (`receipt_lines`, re-keyed)

| Table | Field | Rules |
|---|---|---|
| `receipts` | `attempt_id` PK | One receipt per **successful** attempt (R13) |
| | `transaction_id` | Indexed. Many receipts per transaction (partial purchases) |
| | `completed_at` | = attempt `finalized_at` |
| `receipt_lines` | `attempt_id`, `sku` PK | |
| | `quantity` | > 0. Equals the attempt line's `reserved_quantity` |
| | `unit_price_cents`, `name` | Captured prices. Public `unitPrice` = cents / 100 |

Public receipt for an attempt:
- `itemCount = Σ quantity`
- `totalAmount = Σ quantity × unit_price_cents / 100`
- `lines` holds only this attempt's purchases (FR-014, SC-003).

### Accepted-scan outbox (`accepted_scan_outbox`, replaces `accepted_scan_events`)

| Field | Type | Rules |
|---|---|---|
| `outbox_id` | BIGINT AUTO_INCREMENT PK | Relay ordering hint, not canonical order |
| `event_id` | CHAR(36) UNIQUE | UUIDv7. Becomes the event's `eventId` (dedupe key) |
| `transaction_id`, `station_id`, `sku` | | Event payload |
| `accepted_at` | TIMESTAMP(6) | Scan commit time |
| `published_at` | TIMESTAMP(6) NULL | Set only after broker acknowledgment. Index `(published_at, outbox_id)` |

The outbox row is written in the **same local transaction** as the basket upsert (FR-020). Only
Checkout's relay reads it.

### Catalog projection (`catalog_projection`) and Projection state (`projection_state`)

| Table | Field | Rules |
|---|---|---|
| `catalog_projection` | `sku` PK, `name`, `price_cents`, `catalog_version` | Derived. Rebuildable at any time from Catalog's API |
| `projection_state` (1 row) | `catalog_version` | Version fully loaded. NULL ⇒ never loaded (`EMPTY`) |
| | `loaded_at` | Last full load |
| | `last_verified_at` | Last successful version check. Staleness = now − this |

Freshness states: `EMPTY`, `CURRENT` (verified ≤ 60 s ago), `STALE` (verified > 60 s ago). The
scan rules per state are in [research.md R6](research.md#r6-checkouts-product-lookup-fr-009).

### Completion attempt state machine

```text
                 freeze (local tx; sets active_attempt_id)
                              │
                              ▼
                        ┌───────────┐  reserve result, all zero  ┌──────────┐
                        │ RESERVING │ ─────────────────────────▶ │ REJECTED │ (terminal; 409 INSUFFICIENT_STOCK;
                        └───────────┘                            └──────────┘  basket unchanged; flag cleared)
     age > cancel-after │      │ reserve result, ≥1 unit (prepare: local tx stores
     and unconfirmed    │      │ reserved_quantity = receipt intent)
                        ▼      ▼
              ┌────────────┐  ┌────────────┐  Inventory commit confirmed  ┌───────────┐
              │ CANCELLING │  │ COMMITTING │ ───────────────────────────▶ │ FINALIZED │ (terminal; receipt written;
              └────────────┘  └────────────┘   (finalize: local tx)        └───────────┘  basket reduced; flag cleared;
                    │          forward-only: retried until commit                      COMPLETED if basket empty)
                    │          confirmed; never cancelled
  Inventory cancel  │
  confirmed (or     ▼
  tombstoned)  ┌───────────┐
               │ CANCELLED │ (terminal; no stock change; basket unchanged; flag cleared)
               └───────────┘
```

| From | Event | To | Local effects (one Checkout transaction) |
|---|---|---|---|
| none | Public complete; transaction OPEN, basket non-empty, no active attempt | `RESERVING` | Insert attempt and lines from the basket. Set `active_attempt_id` |
| `RESERVING` | Inventory returns `RESERVED` with Σ reserved > 0 | `COMMITTING` | Store `reserved_quantity` per line (durable receipt intent) |
| `RESERVING` | Inventory returns `RELEASED` (all zero) | `REJECTED` | Clear `active_attempt_id` |
| `RESERVING` | Inventory returns `CANCELLED` (tombstone won) | `CANCELLED` | Clear `active_attempt_id` |
| `RESERVING` | Remote failure, age ≤ `cancel-after` | `RESERVING` | `step_failures++`, schedule `next_retry_at` |
| `RESERVING` | Remote failure, age > `cancel-after` | `CANCELLING` | none |
| `CANCELLING` | Inventory cancel returns `CANCELLED`/`RELEASED` | `CANCELLED` | Clear `active_attempt_id` |
| `CANCELLING` | Inventory cancel returns `409 RESERVATION_COMMITTED` | **impossible by design**: commit is only sent from `COMMITTING`. If observed, set `needs_attention` and stop | none |
| `COMMITTING` | Inventory commit returns `COMMITTED` (including idempotent repeat) | `FINALIZED` | Insert receipt and lines; reduce or delete basket lines; set transaction `COMPLETED` if empty; clear `active_attempt_id`; set `finalized_at` |
| `COMMITTING` | Remote failure (any age) | `COMMITTING` | `step_failures++`, backoff. `needs_attention` after the threshold |

Every transition is a conditional update (`UPDATE … WHERE attempt_id = ? AND state = ?`). When a
joined request and the recovery worker race, exactly one wins and the other re-reads.

---

## Inventory

### Inventory position (`inventory_positions`, altered)

| Field | Type | Rules |
|---|---|---|
| `sku` | VARCHAR(64) PK | |
| `initial_stock` | INT | ≥ 0. Reconciliation baseline. Provisioning, including controlled test restocking (US1-4), adds the same amount to `initial_stock` and `physical_stock` and writes a `PROVISION` movement |
| `physical_stock` | INT | Renamed from `available_stock`. Changed **only** by applying committed sales (or provisioning) |
| `reserved_stock` | INT | Sum of `RESERVED` reservation lines for this SKU, plus committed lines still waiting in `sale_queue` |

Constraints:
- `CHECK (physical_stock >= reserved_stock)`
- `CHECK (reserved_stock >= 0)`

Available-to-reserve = `physical_stock − reserved_stock`. Low-stock reports `physical_stock`, so
a reservation alone does not show as a decrement (US4-1). A committed sale shows there once the
sale applier has run (about 100 ms later); availability is exact throughout, because the sold
quantity stays in `reserved_stock` until it is applied.

### Reservation (`reservations`, new) and Reservation line (`reservation_lines`, new)

| Table | Field | Rules |
|---|---|---|
| `reservations` | `attempt_id` PK | Idempotency key for reserve, commit and cancel |
| | `transaction_id` | Audit |
| | `state` | `RESERVED` \| `COMMITTED` \| `RELEASED` \| `CANCELLED` |
| | `created_at`, `resolved_at` | |
| `reservation_lines` | `attempt_id`, `sku` PK | |
| | `requested_quantity`, `reserved_quantity` | 0 ≤ reserved ≤ requested |

Reservation states:

| From | Command | To | Stock effect |
|---|---|---|---|
| none | reserve, Σ reserved > 0 | `RESERVED` | `reserved_stock += reserved` per SKU |
| none | reserve, Σ reserved = 0 | `RELEASED` | none (nothing held) |
| none | cancel | `CANCELLED` (tombstone, no lines) | none. A later reserve returns this state and reserves nothing |
| `RESERVED` | commit | `COMMITTED` | insert one `sale_queue` row per held line; no position row is touched. The sale applier later does `physical_stock −= Σ queued`, `reserved_stock −= Σ queued` once per SKU per batch and writes one `SALE` movement per line |
| `RESERVED` | cancel | `RELEASED` | `reserved_stock −= reserved` (physical unchanged) |
| `COMMITTED` | commit | `COMMITTED` | none (idempotent repeat) |
| `COMMITTED` | cancel | error `409 RESERVATION_COMMITTED` | none |
| `RELEASED`/`CANCELLED` | commit | error `409 RESERVATION_NOT_COMMITTABLE` | none |
| `RELEASED`/`CANCELLED` | cancel | same state | none (idempotent) |
| any existing | reserve | unchanged | none; returns the stored result (idempotent) |

Each command runs in one local transaction. The command first locks the reservation row
(`SELECT … FOR UPDATE`, or insert-or-lock for new ids). This makes commit and cancel mutually
exclusive (FR-017). Positions are then locked one row at a time by their own conditional updates,
in **descending** SKU order, with no up-front `FOR UPDATE` read. Reserve updates positions last,
just before its commit, and commit touches none. The sale applier uses the same order, so no two
transactions wait on each other in a cycle. Before this change (measured in
`reports/redesign/exploratory/pool-10`), reserve and commit each locked every basket SKU in
ascending order up front, and hot SKUs queued for about 49 ms per locking read.

### Sale queue (`sale_queue`, new)

| Field | Type | Rules |
|---|---|---|
| `queue_id` | BIGINT AUTO_INCREMENT PK | Commit order |
| `attempt_id`, `sku` | | `UNIQUE (attempt_id, sku)`: a commit queues each line once |
| `quantity` | INT | ≥ 1, the line's reserved quantity |
| `committed_at` | TIMESTAMP(6) | Becomes the `SALE` movement's `occurred_at` |

Written in the commit's transaction; read and deleted by the sale applier (`FOR UPDATE SKIP
LOCKED`, up to 2,000 rows per batch, every 100 ms) in the transaction that applies them.

### Stock movement (`inventory_movements`, altered)

| Field | Type | Rules |
|---|---|---|
| `movement_id` | BIGINT PK | |
| `attempt_id` | VARCHAR(64) **NOT NULL** for sales | Every committed decrement references a durable attempt (FR-019). Provisioning rows use `movement_type = 'PROVISION'` and a NULL attempt |
| `movement_type` | VARCHAR(16) | `SALE` \| `PROVISION` |
| `sku`, `quantity_delta`, `physical_after`, `occurred_at` | | `UNIQUE (attempt_id, sku)` for `SALE` prevents duplicate decrements |

### Name projection (`catalog_names`)

Fields: `sku` PK, `name`, `catalog_version`, plus the single-row `projection_state`. Same
semantics as Checkout's projection, without prices (R7).

### Inventory invariants

- `0 ≤ reserved_stock ≤ physical_stock` for every SKU, at all times.
- `reserved_stock(sku) = Σ reservation_lines.reserved_quantity` over reservations in `RESERVED`,
  plus `Σ sale_queue.quantity` for the SKU.
- `initial_stock − physical_stock = Σ SALE movements` (in absolute value) for the SKU, adjusted by
  any `PROVISION` movements.

---

## Analytics

### Ingested scan (`ingested_scans`, replaces `processed_scans` + `analytics_events`)

| Field | Type | Rules |
|---|---|---|
| `event_id` | CHAR(36) PK | Dedupe key from the event |
| `sequence_number` | BIGINT UNIQUE | Canonical order, assigned on first ingest. Immutable |
| `sku` | VARCHAR(64) | |
| `accepted_at` | TIMESTAMP(6) | From the event; informational, not used for ordering |
| `ingested_at` | TIMESTAMP(6) | |

### Ingest state (`ingest_state`, single row)

`last_sequence BIGINT`, locked `FOR UPDATE` by each batch transaction. This serializes sequence
assignment (fixes F2).

### Popularity snapshot (`popularity_snapshots`), Snapshot item (`popularity_snapshot_items`), Pointer (`published_snapshot`)

| Table | Field | Rules |
|---|---|---|
| `popularity_snapshots` | `window_end` PK | Boundary sequence (1, 500, 1000, …) |
| | `window_start` | `max(1, window_end − 999)` |
| | `computed_at` | Public `computedAt` |
| `popularity_snapshot_items` | `window_end`, `sku` PK | Written once, never updated |
| | `name` | From the name projection at publication time |
| | `scan_count`, `rank_position` | Rank by `scan_count DESC, sku ASC` |
| `published_snapshot` (1 row) | `current_window_end` | NULL ⇒ empty result. Flipped in the same transaction that writes the snapshot |

Window rules (FR-023, US4-4):
- Window size is 1,000 and the refresh interval is 500.
- The first publication occurs at sequence 1.
- Windows overlap, so a scan contributes once to each window containing it.

Old snapshots may be pruned, keeping at least the current and the previous one.

### Ingest rejection (`ingest_rejections`)

Fields: `kafka_offset` PK, `reason`, `raw_value`, `rejected_at`. Records unparseable or
unsupported-version messages before they are acknowledged. These are never counted as scans.

### Name projection

`catalog_names` plus `projection_state`, as in Inventory.

---

## Cross-service relationships (logical only)

```text
Catalog.sku ──(projection API)──▶ Checkout.catalog_projection.sku ──▶ basket_lines.sku (price captured)
                              └──▶ Inventory.catalog_names.sku,  Analytics.catalog_names.sku
Checkout.completion_attempts.attempt_id ──(reserve/commit/cancel API)──▶ Inventory.reservations.attempt_id
                                                                        └──▶ inventory_movements.attempt_id
Checkout.accepted_scan_outbox.event_id ──(Kafka)──▶ Analytics.ingested_scans.event_id
```

## Settled-state reconciliation (FR-019, SC-005)

Each owner exports through its own credentials, and `combine-reconciliation` compares the exports:

| Check | Source | Pass condition |
|---|---|---|
| R-1 | Inventory | `physical_stock ≥ reserved_stock ≥ 0` for every SKU |
| R-2 | Inventory | No reservation in `RESERVED` |
| R-3 | Checkout | No attempt in `RESERVING`, `COMMITTING` or `CANCELLING`. `active_attempt_id IS NULL` everywhere |
| R-4 | Inventory × Checkout | `sale_queue` is empty, and for every SKU: `initial_stock − physical_stock = Σ receipt_lines.quantity` |
| R-5 | Inventory × Checkout | The set of `COMMITTED` reservation ids equals the set of receipt `attempt_id`s, and quantities match per SKU |
| R-6 | Checkout × Analytics | Count of outbox events (published + pending) = count of `ingested_scans`, after the backlog drains |
