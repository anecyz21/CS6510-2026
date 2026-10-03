# HTTP Contract and Layer Mapping

The authoritative external contract is [spec/self-checkout-openapi.yaml](../../../spec/self-checkout-openapi.yaml).
This document does not alter it; it records how the layered server preserves it.

| Endpoint | API responsibility | Delegated owner | Required outcomes |
| --- | --- | --- | --- |
| `GET /items` | Route and encode catalog response | Data access through a catalog query boundary | 200 catalog response |
| `POST /transactions` | Validate and decode station request | Transactions | 201 transaction or 400 invalid request |
| `POST /transactions/{id}/items` | Decode item scan request | Transactions, then analytics for successful scans | 200; 404; 409 |
| `POST /transactions/{id}/complete` | Route completion request | Transactions | 200 receipt for fulfilled units, optionally including retained unavailable items; 404; 409 for invalid state |
| `GET /transactions/{id}` | Route status lookup | Transactions | 200 transaction or 404 |
| `GET /inventory/low-stock` | Parse optional threshold | Data access through inventory query boundary | 200 low-stock response |
| `GET /analytics/popular-items` | Parse optional limit | Analytics | 200 popularity response |

## Error Contract

The API layer encodes validation, missing-resource, and invalid-state failures as the OpenAPI
`ApiError` shape: `error` and `message`. Domain and persistence failures do not cross the HTTP
boundary directly.

## Partial-Completion Behavior

For a non-empty open transaction, completion is a successful `200` outcome even when some scanned
units are unavailable. The documented receipt fields describe only the fulfilled units. The response
also includes an optional `unavailableItems` array; each entry has `sku`, `name`, and `quantity` for
a unit count retained in the basket. This adds no required field and leaves all documented response
fields unchanged.

The transaction layer removes fulfilled units from the pending basket and decrements exactly those
units atomically. It retains every unavailable unit and keeps the transaction `OPEN` until its
basket is empty. When no units are available, the receipt has zero fulfilled lines and the optional
array identifies all retained items.

## Dependency Rules

- API handlers do not read or mutate stored state directly.
- Transaction workflows own lifecycle changes and request atomic inventory mutation through data
  access.
- Analytics owns scan-window state and rankings; it does not change checkout state.
- Data access exposes domain-oriented operations, not HTTP request or response objects.
