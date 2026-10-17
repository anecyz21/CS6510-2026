# Data Model: Database-Coordinated Domain Services

## Ownership and Interfaces

| Schema | Owner | Write authority | Permitted external interface |
| --- | --- | --- | --- |
| `catalog` | Catalog | Catalog service only | Read-only active-item view |
| `checkout` | Checkout | Checkout service only | Read-only accepted-scan outbox view |
| `inventory` | Inventory | Inventory service and fulfillment procedure only | `fulfill_checkout` execute permission; low-stock view |
| `analytics` | Analytics | Analytics service only | Read-only published-popularity view |

## Catalog Domain

### Catalog Item

- `sku`: immutable unique identifier; nonblank; maximum 64 characters.
- `name`: nonblank display name; maximum 255 characters.
- `price_cents`: non-negative integer monetary value.
- `active`: whether the item can be newly scanned.

Checkout reads Catalog's active-item view and copies the price into its basket line, so a later
price change does not alter an existing basket or receipt.

## Checkout Domain

### Checkout Transaction and Basket Line

- `transaction_id`: immutable unique identifier; `station_id`: nonblank origin identifier.
- `status`: `OPEN`, `COMPLETED`, or `CANCELLED`; lifecycle timestamps are retained.
- Basket-line `(transaction_id, sku)` is unique; `quantity` is positive and `unit_price_cents`
  is non-negative.

### Accepted Scan Outbox Record

- `scan_id`: globally unique, monotonically ordered identifier.
- `transaction_id`, `sku`, `accepted_at`, and `published_at` identify the accepted scan.

The basket update and accepted-scan outbox record commit atomically. The outbox is append-only;
Analytics receives only read access.

## Inventory Domain

### Inventory Position and Movement

- Position fields: `sku`, `initial_stock`, `available_stock`, `reserved_stock`.
- All quantities are non-negative and `reserved_stock <= available_stock <= initial_stock`.
- Movement fields: `movement_id`, `sku`, `transaction_id`, `quantity_delta`, `stock_after`, and
  `occurred_at`; final `stock_after` is never negative.

### Fulfillment Result

- `transaction_id`, fulfilled positive quantity per SKU, and unavailable positive quantity per
  SKU.

The Inventory-owned `fulfill_checkout` procedure locks SKU rows in deterministic SKU order,
updates inventory and movement records, returns the result, and does not commit the caller's
transaction.

## Analytics Domain

### Processed Scan and Window Event

- `scan_id`: unique primary key copied from Checkout's outbox; `processed_at` records idempotent
  consumption.
- Event fields: `scan_id`, positive contiguous `sequence_number`, `sku`, and `accepted_at`.

### Popularity Snapshot

- Snapshot fields: `window_start`, `window_end`, `window_size`, `slide_interval`, `computed_at`.
- Item fields: `sku`, positive `scan_count`, positive `rank_position`.
- Rankings are count descending and SKU ascending for ties; snapshots are published atomically.

## Cross-Domain State Transitions

```text
OPEN checkout
  └─ scan accepted → basket line + accepted-scan outbox committed
       └─ Analytics records unique scan → ordered window event → complete snapshot

OPEN checkout
  └─ complete requested → Inventory fulfillment procedure
       ├─ fulfilled lines → receipt persisted, stock/movement persisted
       └─ unavailable lines → retained in OPEN basket
```
