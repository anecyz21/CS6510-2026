# Event Contract: `catalog.products.v1`

**Producer**: Catalog (`ProductPublisher`, version-based outbox, research R2) |
**Consumers**: Checkout, Inventory, Analytics (catalog projections, research R3) |
**Java record**: `service-contracts` → `edu.northeastern.cs6510.contracts.catalog.v1.ProductEventV1`

## Topic

| Property | Value | Why |
|---|---|---|
| Partitions | 1 | Each consumer stores one offset and compares it with one end offset at startup |
| Cleanup | `compact` | The latest record per SKU is kept forever, so a new consumer can rebuild a full copy |
| Key | `sku` (UTF-8) | The unit that compaction keeps |

## Message `ProductChanged`

Headers follow the envelope in [README.md](README.md) (`producer=catalog`, no `correlationId`).

```json
{
  "schemaVersion": 1,
  "messageId": "0192d0a1-5b6c-7e21-8f40-3a9b7c1d2e10",
  "messageType": "ProductChanged",
  "catalogVersion": 3,
  "sku": "SKU-000042",
  "name": "Oat Milk 1L",
  "priceCents": 399,
  "active": true
}
```

| Field | Rules |
|---|---|
| `catalogVersion` | The `catalog_metadata.catalog_version` being published, ≥ 1. Every SKU is republished at each new version |
| `sku` | Equal to the record key |
| `name` | 1–255 characters |
| `priceCents` | Integer ≥ 0. Exact cents, never a float |
| `active` | `false` means the SKU is no longer for sale. Consumers delete it from their projection, and scans then return the existing unknown-item error |

## Producer guarantees (Catalog)

1. **Complete per version**: every row of `catalog_items` is sent for each version, active and
   inactive alike. `catalog_publication.published_version` advances only after every record of
   that version is acknowledged (`acks=all`, idempotent producer).
2. **Repeats are safe**: a crash mid-publication republishes the whole version. Consumers skip
   records whose `catalogVersion` is lower than the one stored for that SKU, and apply equal or
   higher ones.
3. **Self-healing after a broker reset**: if `published_version` is set but the topic's end
   offset is 0, Catalog republishes. `POST /internal/v1/ops/republish` forces a republish.

## Consumer obligations

1. Store `topic_offset` in the same local transaction as the projection rows, and seek from it
   on assignment. Never rely on committed group offsets (research R3).
2. Report not ready until the end offset captured at assignment has been reached and at least
   one product is held. Until then, answer a lookup miss with `503 SERVICE_UNAVAILABLE`, never
   "unknown item", because readiness alone does not stop traffic.
3. Apply a record only if its `catalogVersion` ≥ the stored version for that SKU. Treat
   `active=false` as a delete.
4. Checkout projects `name` and `priceCents`. Inventory and Analytics project only `name`.
5. On a record that cannot be parsed, dead-letter it and continue (README). Validation failures,
   such as a negative price or a blank name, are dead-lettered as well and are never applied.

## Fixtures

`fixtures/messages/product-changed-v1.json` (active) and
`fixtures/messages/product-changed-inactive-v1.json`. The 004 fixtures `catalog-version-v1.json`
and `product-page-v1.json` are deleted along with the HTTP API.
