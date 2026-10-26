# Public API Amendment: Partial Completion, Busy Scans and Dependency Outcomes

**Source of truth**: [`spec/self-checkout-openapi.yaml`](../../../spec/self-checkout-openapi.yaml)
(was `info.version: "1.0.0"`) | **Target version**: `1.2.0` (A1–A6); `1.2.1` after A7 |
**Authority**: spec Clarifications 2026-10-23, FR-006, FR-013–FR-016, FR-034

This document lists every change to the canonical public contract that this feature requires. Per
FR-034 and the plan's phase ordering, these amendments are applied to the YAML **before any
implementation code changes**. None of them adds a path, schema, field, required property or
lifecycle status. Every error uses the existing `ApiError {error, message}` body. The load client
and mock server are not modified.

Status legend: **Approved** = authorized by the spec's clarifications (A1–A5), by the user's
explicit planning decision of 2026-10-24 (A6), or by the user's decision of 2026-10-26 (A7).

## Amendments

### A1: Completion description: partial fulfillment (Approved)

`POST /transactions/{transactionId}/complete` → `description` is replaced with:

> Purchases every scanned unit that is currently available and decrements each purchased SKU's
> stock by the purchased quantity. Units that are unavailable at completion time are not charged
> and remain in the open basket for a later completion call; no shortage fields are added to the
> response. The transaction becomes `COMPLETED` only when its basket is empty after a purchase.
> Each successful call returns a receipt for the units purchased by **that call only**: `lines`,
> `itemCount` and `totalAmount` exclude purchases from earlier calls on the same transaction, while
> `transactionId`, `stationId` and `startedAt` stay those of the transaction and `completedAt` is
> the time this purchase was finalized. Payment is assumed to always succeed.

`responses."200".description` becomes: "Available units purchased (the transaction may remain
OPEN if some units were unavailable)".

### A2: Completion `409 INSUFFICIENT_STOCK` (Approved)

Extend the `409` description for completion:

> Transaction cannot be completed: `TRANSACTION_NOT_OPEN` (already completed or cancelled),
> `EMPTY_BASKET` (no scanned units remain), or `INSUFFICIENT_STOCK` (none of the remaining units
> is available; no receipt is issued, no stock changes, and the basket stays open and unchanged so
> the call may be retried later).

This replaces the older guidance in `specs/001-self-checkout-api/contracts/error-codes.md` that
`INSUFFICIENT_STOCK` is terminal and cancels the transaction.

### A3: Scan `409 TRANSACTION_BUSY` (Approved)

Extend the `409` description for `POST /transactions/{transactionId}/items`:

> `TRANSACTION_NOT_OPEN` when the transaction is completed or cancelled; `TRANSACTION_BUSY` when a
> completion of this transaction is still being processed. A busy scan is rejected immediately, is
> not queued, does not change the basket, and does not count toward popular items; the client may
> scan again after the completion call returns.

The public `status` remains `OPEN` while busy, and no new enum value is added.

### A4: Completion `503 SERVICE_UNAVAILABLE` on unconfirmed outcome (Approved)

Add a `503` response (schema `ApiError`) to completion:

> The purchase outcome could not be confirmed within the server's completion deadline (default
> 5 seconds) because a dependency was unavailable. This is **not** a cancellation: the server keeps
> the attempt and finishes or safely abandons it in the background without ever selling a unit
> twice. A later call returns the result of the transaction's current state — `409
> TRANSACTION_NOT_OPEN` if the purchase finished and emptied the basket, a receipt for any units
> still in the basket, or `503` again while the dependency is still unavailable.

### A5: Concurrent completion calls (Approved)

Append to the completion description:

> If a completion call arrives while a previous completion of the same transaction is still being
> processed, it does not start a second purchase; it waits, within the same deadline, for that
> purchase's result and returns it (`200`, `409 INSUFFICIENT_STOCK`, or `503`). Once a purchase has
> finished, the server cannot distinguish a retry of a lost response from a new completion request:
> a later call purchases only units still in the basket and never re-sells purchased units.

### A6: Dependency-unavailable `503` on every operation (Approved 2026-10-24)

Add a `503` response (schema `ApiError`, `error: SERVICE_UNAVAILABLE`) to all seven operations:

> The service owning this operation, or data it requires, is temporarily unavailable; no business
> effect was confirmed. Retry later.

| Operation | Concrete trigger in this design |
|---|---|
| `GET /items` | Catalog unreachable from the gateway |
| `POST /transactions` | Checkout unreachable |
| `POST /transactions/{id}/items` | Checkout unreachable. Also: the SKU is not in a stale or empty catalog projection **and** Catalog cannot be reached to verify it (R6). Returning `SKU_NOT_FOUND` here would disguise a dependency failure (FR-029). |
| `GET /transactions/{id}` | Checkout unreachable |
| `POST /transactions/{id}/complete` | Checkout unreachable (A4 covers the Inventory case) |
| `GET /inventory/low-stock` | Inventory unreachable, or an alerted SKU has no projected name |
| `GET /analytics/popular-items` | Analytics unreachable |

The user approved A6 during planning on 2026-10-24, which satisfies FR-034's requirement to
document this outcome before implementation. It replaces today's undocumented `500` for these
cases. A `503` never accompanies a business effect, and the client may retry.

### A7: Document the existing `400` responses on scan and popular items (Approved 2026-10-26)

A documentation-only PATCH (`info.version` `1.2.0` → `1.2.1`). It adds a `400` response
(schema `ApiError`) to two operations whose `400` codes were already in the error catalog below
and already returned by the services, but were missing from the YAML:

| Operation | `400` description | Code | Source |
|---|---|---|---|
| `POST /transactions/{transactionId}/items` | Invalid request (`MISSING_SKU`, `sku` missing or blank) | `MISSING_SKU` | `ScanItemRequest` already requires `sku`. The gateway has rejected a missing `sku` since the service-based design |
| `GET /analytics/popular-items` | Invalid request (`INVALID_LIMIT`, `limit` below 1 or not an integer) | `INVALID_LIMIT` | Feature 001 `error-codes.md` (FR-027); restored by task T111 |

No path, parameter, schema, field or behavior changes. The gap was found by the full contract
test (task T121, `reports/validation-2026-10-26/`).

## Error catalog (after amendment)

| Code | Status | Operation(s) | Raised when | Body |
|---|---|---|---|---|
| `MISSING_STATION_ID` | 400 | start | `stationId` missing or blank | ApiError |
| `MISSING_SKU` | 400 | scan | `sku` missing or blank | ApiError |
| `INVALID_LIMIT` | 400 | popular items | `limit < 1` | ApiError |
| `TRANSACTION_NOT_FOUND` | 404 | scan, complete, get | Unknown transaction id | ApiError |
| `SKU_NOT_FOUND` | 404 | scan | SKU absent from a **current** catalog projection, or Catalog confirms absence | ApiError |
| `TRANSACTION_NOT_OPEN` | 409 | scan, complete | Status `COMPLETED` or `CANCELLED` | ApiError |
| `TRANSACTION_BUSY` | 409 | scan | Unresolved completion attempt owns the basket (A3) | ApiError |
| `EMPTY_BASKET` | 409 | complete | Open transaction with no basket units | ApiError |
| `INSUFFICIENT_STOCK` | 409 | complete | Every remaining unit unavailable. Basket unchanged, no receipt (A2) | ApiError |
| `SERVICE_UNAVAILABLE` | 503 | complete (A4); all operations (A6) | Outcome or dependency unconfirmed within the deadline | ApiError |

**Precedence on completion** (first matching rule wins):
1. `TRANSACTION_NOT_FOUND`
2. `TRANSACTION_NOT_OPEN`
3. Join an active attempt (A5)
4. `EMPTY_BASKET`
5. Start a new attempt, ending in `200`, `INSUFFICIENT_STOCK` or `SERVICE_UNAVAILABLE`

**Precedence on scan**:
1. `MISSING_SKU` (gateway)
2. `TRANSACTION_NOT_FOUND`
3. `TRANSACTION_NOT_OPEN`
4. `TRANSACTION_BUSY`
5. `SKU_NOT_FOUND` / `SERVICE_UNAVAILABLE`
6. `200`

`message` text is not contractual. It names the identifier and never exposes attempt ids,
reservation quantities or internal states.

## Unchanged public behavior (compatibility checklist for SC-001)

- All request and response schemas, field names, types and required lists are unchanged.
- `Transaction.status` enum stays `[OPEN, COMPLETED, CANCELLED]`. No pending status is added.
- A scan never reserves or decrements stock and is never rejected for low stock (FR-008).
- `GET /inventory/low-stock` reports physical stock, so a reservation is not a decrement. `name`
  is the catalog name (fixes F4).
- `GET /analytics/popular-items` counts accepted scans, not purchases. It returns a complete
  snapshot and a valid empty result before the first publication.
- No client idempotency header or request id is introduced.

## Documents to mark superseded (R19)

- `specs/001-self-checkout-api/contracts/error-codes.md`: the "refusal is terminal" and
  `CANCELLED` note for `INSUFFICIENT_STOCK`, and the scan-time claims.
- `domain-services/deployment/ARCHITECTURE.md`: database-only coordination.

## Documents to rewrite (R19)

- `architecture-characteristics.md`: rewrite the all-or-nothing completion statement as partial
  completion. Available units are sold, unavailable units stay in the open basket, and receipts
  are per attempt. Rewrite the single-MySQL-transaction statement as the completion saga
  ([research.md R8](../research.md#r8-completion-saga)). This file states current requirements,
  so it is corrected in place rather than marked superseded. Its other text is preserved.
