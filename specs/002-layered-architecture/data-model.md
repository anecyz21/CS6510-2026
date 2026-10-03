# Data Model: Layered Server Architecture

## Catalog Item

| Field | Description | Validation |
| --- | --- | --- |
| sku | Stable item identifier | Non-empty and unique |
| name | Display name | Non-empty |
| price | Unit price | Non-negative monetary value |

## Inventory Record

| Field | Description | Validation |
| --- | --- | --- |
| sku | Associated catalog item | Must reference a catalog item |
| quantity | Current sellable units | Integer greater than or equal to zero |

The data-access layer owns inventory reads and atomic changes. For each completion, it determines
the fulfillable quantity per SKU while holding the inventory mutation boundary, decrements only
those units, and never allows quantity below zero.

## Transaction

| Field | Description | Validation |
| --- | --- | --- |
| transactionId | Server-generated transaction identifier | Unique and non-empty |
| stationId | Checkout station identifier | Non-empty |
| status | Lifecycle state | `OPEN`, `COMPLETED`, or `CANCELLED` |
| startedAt | Creation timestamp | Set when created |
| lines | Pending scanned item units | May grow only while open; fulfilled units are removed at completion |

```text
OPEN --complete with all units fulfilled------------------------> COMPLETED
OPEN --complete with some or no units fulfilled----------------> OPEN (retains unavailable units)
OPEN --cancel (if supported internally)------------------------> CANCELLED
COMPLETED or CANCELLED --scan or complete----------------------> rejected (409)
```

Only the transactions layer changes transaction state. Completion is rejected when the transaction
is missing, not open, or empty. Insufficient stock is not a rejection condition: each unavailable
unit remains in the basket for a later attempt.

## Completion Result

| Field | Description | Validation |
| --- | --- | --- |
| receipt | Documented receipt fields for fulfilled units | Includes only units whose stock was decremented |
| unavailableItems | Optional list of retained item shortages | One entry per SKU with a positive retained quantity |

An unavailable-item entry identifies the SKU, display name, and retained quantity. The API layer
encodes this as an optional extension to the receipt response; clients that only consume documented
receipt fields remain compatible.

## Scan Event and Scan Window

| Field | Description | Validation |
| --- | --- | --- |
| sequence | Monotonic global scan number | Unique and increasing |
| sku | Scanned catalog item | Must reference a catalog item |
| scannedAt | Scan timestamp | Set when scan succeeds |

The analytics layer owns the bounded scan window and latest ranking snapshot. The snapshot includes
window size, slide interval, start/end sequence numbers, computed time, and ranked counts.

## Layer Boundary

| Layer | Owns | May depend on |
| --- | --- | --- |
| API | HTTP routes, JSON translation, contract errors | Transactions, analytics, domain values |
| Transactions | Lifecycle, scan workflow, atomic completion | Data access, analytics, domain values |
| Analytics | Scan recording and hopping-window rankings | Data access or scan store, domain values |
| Data access | Catalog, transaction, inventory, and scan persistence | Domain values only |
