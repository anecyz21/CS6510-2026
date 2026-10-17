# Public and Database Boundary Contract

## Public Gateway Contract

The gateway is the sole public HTTP entry point and preserves the authoritative
[`self-checkout-openapi.yaml`](../../../spec/self-checkout-openapi.yaml) contract without new
public endpoints or changed response shapes.

| Public route | Owning domain | Gateway responsibility |
| --- | --- | --- |
| `GET /items` | Catalog | Route request and preserve catalog response shape. |
| `POST /transactions` | Checkout | Route request and preserve creation response. |
| `POST /transactions/{id}/items` | Checkout | Route request and preserve scan response. |
| `POST /transactions/{id}/complete` | Checkout | Route request and preserve receipt/partial-completion response. |
| `GET /transactions/{id}` | Checkout | Route request and preserve status response. |
| `GET /inventory/low-stock` | Inventory | Route request and preserve alert response. |
| `GET /analytics/popular-items` | Analytics | Route request and preserve snapshot response. |

The gateway may call a service to route a public request. It MUST NOT read the centralized
database or add business decisions.

## Domain-Service Communication Rule

Catalog, Checkout, Inventory, and Analytics MUST NOT call each other over HTTP, RPC, shared
application code, queues, or brokers. Their only inter-domain interfaces are the following
database interfaces.

| Consumer | Provider-owned interface | Access | Purpose |
| --- | --- | --- |
| Checkout | `catalog.active_items` view | Read-only | Validate SKU and capture current price. |
| Checkout | `inventory.fulfill_checkout` procedure | Execute | Atomically decide fulfillment and mutate inventory. |
| Analytics | `checkout.accepted_scan_outbox` view | Read-only | Read durable accepted scans in order. |
| Gateway | None | None | Gateway uses only service transport endpoints. |

## Permission Contract

- Catalog writes only `catalog`; Checkout writes only `checkout`; Inventory writes only
  `inventory`; Analytics writes only `analytics`.
- Every service account receives only schema-local write permissions plus the documented
  read/execute grants above.
- The Inventory fulfillment procedure may modify only Inventory-owned tables and MUST NOT commit
  the caller's transaction.
- Analytics records its own `processed_scan` checkpoint and MUST NOT update the Checkout outbox.
- Database grants are tested by attempting prohibited writes and confirming they fail.

## Readiness Contract

Each executable exposes a health/readiness response that succeeds only when its own configuration
and permitted database access are usable. Readiness MUST NOT depend on another domain-service
process being online. Gateway readiness additionally verifies configured routes but not domain
business state.
