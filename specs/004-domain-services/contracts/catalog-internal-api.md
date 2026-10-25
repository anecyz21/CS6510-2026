# Catalog Internal API v1

**Provider**: Catalog service | **Consumers**: Checkout (price + name projection), Inventory and
Analytics (name projections) | **Transport**: HTTP/1.1 JSON on the internal network (port 8081) |
**Java records**: `service-contracts` → `edu.northeastern.cs6510.contracts.catalog.v1`

The public `GET /items` route is served by Catalog through the gateway and is not part of this
contract. The internal paths below are not routed by the gateway.

## Common rules

- Paths are versioned (`/internal/v1/...`). Additive response fields are allowed within v1, and
  consumers ignore unknown fields. Any breaking change is published as `/internal/v2/...`
  alongside v1 (research R16).
- Prices are integer cents (`priceCents`), never floating point.
- Errors use `{ "error": "...", "message": "..." }`.
- All operations are read-only and safe to retry.

## `GET /internal/v1/catalog-version`

Cheap freshness probe, polled every 15 s by each consumer.

```json
200 { "catalogVersion": 1, "updatedAt": "2026-10-24T12:00:00.000000Z", "activeItemCount": 2000 }
```

Consumer timeouts: 300 ms connect, 2 s read. A failure leaves the consumer's projection unchanged
and its `last_verified_at` is not advanced (it may become `STALE`).

## `GET /internal/v1/products?afterSku={sku}&limit={n}`

Pages through **active** products in ascending SKU order for a full projection load. `limit`
defaults to 500, maximum 1000. `afterSku` is omitted for the first page.

```json
200 {
  "catalogVersion": 1,
  "items": [ { "sku": "SKU-000001", "name": "Catalog item 1", "priceCents": 101 } ],
  "nextAfterSku": "SKU-000500"
}
```

- `nextAfterSku` is `null` on the last page.
- Each page carries `catalogVersion`. If the version changes between pages, the consumer discards
  the staged load and restarts. It swaps its projection only after a complete load at a single
  version.
- `400 INVALID_LIMIT` if `limit < 1` or `> 1000`.

## `GET /internal/v1/products/{sku}`

Single lookup, used only by Checkout's stale or empty-projection miss path (R6).

```json
200 { "catalogVersion": 1, "sku": "SKU-000123", "name": "Catalog item 123", "priceCents": 223 }
404 { "error": "SKU_NOT_FOUND", "message": "SKU SKU-999999 is not in the catalog." }
```

Inactive SKUs return `404`. Consumer timeout: 300 ms total. A transport failure means
"unverifiable", never "not found" (FR-029).

## Consumer projection policy (normative for consumers)

| State | Condition | Scan (Checkout) | Low-stock / popular names (Inventory / Analytics) |
|---|---|---|---|
| `EMPTY` | Never loaded | Lookup per SKU. 503 if unreachable | 503 for any SKU lacking a name |
| `CURRENT` | Version verified ≤ 60 s ago | Projection only. Miss → `404 SKU_NOT_FOUND` | Projection |
| `STALE` | Last verification > 60 s ago | Hit → accept at projected price. Miss → lookup; 503 if unreachable | Projection (names are stable), with a warning in health detail |

Reload trigger: the version probe returns a version different from `projection_state.catalog_version`.
Rebuild: truncating the projection forces `EMPTY` and a full load on the next probe.
