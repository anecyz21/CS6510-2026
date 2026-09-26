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

The data-access layer owns inventory reads and atomic changes. Completion first verifies all
required quantities and then applies the complete set as one outcome; it never partially decrements.

## Transaction

| Field | Description | Validation |
| --- | --- | --- |
| transactionId | Server-generated transaction identifier | Unique and non-empty |
| stationId | Checkout station identifier | Non-empty |
| status | Lifecycle state | `OPEN`, `COMPLETED`, or `CANCELLED` |
| startedAt | Creation timestamp | Set when created |
| lines | Scanned item units | May grow only while open |

```text
OPEN --complete with non-empty basket and sufficient inventory--> COMPLETED
OPEN --cancel (if supported internally)------------------------> CANCELLED
COMPLETED or CANCELLED --scan or complete----------------------> rejected (409)
```

Only the transactions layer changes transaction state. Completion is rejected when the transaction
is missing, not open, empty, or lacks sufficient inventory.

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
