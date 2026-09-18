# Interface Contracts

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Date**: 2026-09-17

## Normative source

The authoritative contract is **[`spec/self-checkout-openapi.yaml`](../../../spec/self-checkout-openapi.yaml)**
(OpenAPI 3.0.3, contract version **1.1.0**) at the repository root. It is not duplicated here.
Where this directory and that file disagree, the file wins (Constitution Principle I).

This directory adds only what a schema cannot express: which outcomes each operation must
produce, and which requirement each one answers to.

## Conformance matrix

Every row is a Gate 1 contract test (see [quickstart.md](../quickstart.md)). "→" separates the
trigger from the required outcome. Requirement numbers track [spec.md](../spec.md).

### Catalog

| Operation | Outcome | Requirement |
|---|---|---|
| `GET /items` | 200, every catalog item with `sku`, `name`, `price` | FR-001 |
| `GET /items` twice in one run | identical membership and prices both times | FR-002 |

### Transactions

| Operation | Outcome | Requirement |
|---|---|---|
| `POST /transactions` with `stationId` | 201, status `OPEN`, `itemCount` 0, `runningTotal` 0, `startedAt` set | FR-003 |
| `POST /transactions` without `stationId` | 400 `MISSING_STATION_ID` | FR-004 |
| `POST /transactions/{id}/items`, known SKU, open transaction | 200, `itemCount` +1, `runningTotal` + unit price | FR-005, FR-006 |
| same, SKU with no unclaimed inventory left | 200 — the unit is recorded, no claim taken | FR-007, FR-017 |
| same, unknown transaction id | 404 `TRANSACTION_NOT_FOUND` | FR-008 |
| same, unknown SKU | 404 `SKU_NOT_FOUND` | FR-008 |
| same, transaction not open | 409 `TRANSACTION_NOT_OPEN` | FR-009 |
| `POST /transactions/{id}/complete`, every scanned unit claimed | 200 + receipt, one line per distinct SKU, stock decremented, claims released | FR-010, FR-011 |
| same, unknown transaction id | 404 `TRANSACTION_NOT_FOUND` | FR-012 |
| same, empty basket | 409 `EMPTY_BASKET` | FR-012 |
| same, already completed or cancelled | 409 `TRANSACTION_NOT_OPEN`, stock unchanged | FR-012 |
| same, any scanned unit without a claim | 409 `INSUFFICIENT_STOCK`, no receipt, no stock change, transaction not completed | FR-013 |
| `GET /transactions/{id}` | 200, current station / status / `itemCount` / `runningTotal` / `startedAt` | FR-014 |
| same, unknown id | 404 `TRANSACTION_NOT_FOUND` | FR-014 |
| same, after an insufficient-stock refusal | 200 with status `CANCELLED` — the refusal is terminal | FR-013, R6 |

### Inventory

| Operation | Outcome | Requirement |
|---|---|---|
| `GET /inventory/low-stock` | 200 with `threshold`, `generatedAt`, alerts for every SKU **strictly below** threshold | FR-019 |
| same, a SKU whose stock equals the threshold | that SKU is **not** in `alerts` | FR-019 |
| same, nothing below threshold | 200 with an empty `alerts` array — not an error | FR-019 |
| same, `?threshold=0` | 200 with an empty `alerts` array — stock is never negative | FR-015, FR-019 |
| same, `?threshold=N` | 200 reflecting N; the configured default unchanged for later queries | FR-020 |
| same, while units are claimed but unsold | `currentStock` reports **physical** stock | FR-021 |

### Analytics

| Operation | Outcome | Requirement |
|---|---|---|
| `GET /analytics/popular-items` | 200 with `windowSize`, `slideInterval`, `windowStart`, `windowEnd`, `computedAt`, ranked `items` | FR-023, FR-024 |
| same | ranked by `scanCount` descending, `rank` from 1 | FR-025 |
| same, `?limit=N` | at most N items | FR-025 |
| same, `?limit=0` or negative | 400 — `limit >= 1` | FR-027 |
| same, at a position between two boundaries | the snapshot from the most recently completed boundary | FR-023 |
| same, twice between boundaries | byte-identical results | FR-023 |
| same, before the first boundary at `windowSize` | 200, empty ranking, `windowStart = windowEnd = 0` | FR-024 |
| after scans in transactions never completed, cancelled, or refused | those scans still counted | FR-026 |

### Configuration

| Condition | Outcome | Requirement |
|---|---|---|
| `windowSize < 1`, or `slideInterval` outside `1..windowSize` | startup fails rather than silently correcting | FR-027, R9 |

## Boundary rules

These hold for every operation above and are verified as part of Gate 1.

- **Synchronous** — every operation is request/response. No 202, no polling, no callback,
  regardless of internal mechanism (FR-028).
- **Claims invisible** — no operation exposes, queries, or manipulates inventory claims, and no
  payload carries claim state. The only externally visible consequences are the
  `INSUFFICIENT_STOCK` conflict and the resulting `CANCELLED` status (FR-031).
- **Uniform errors** — every non-2xx body is an `ApiError` with a machine-readable `error` and
  a human-readable `message` (FR-029). See [error-codes.md](./error-codes.md).
- **Money** — amounts are JSON numbers exact to the cent; totals equal the sum of their parts
  (FR-030).
- **No extra surface** — no operation, query parameter, header, or field beyond the contract. In
  particular, no reconciliation or debug endpoint is added for testing convenience (FR-031,
  FR-032).
