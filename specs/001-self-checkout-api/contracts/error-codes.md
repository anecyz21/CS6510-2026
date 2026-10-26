# Error Code Catalog

> **Superseded by
> [`specs/004-domain-services/contracts/public-api-amendment.md`](../../004-domain-services/contracts/public-api-amendment.md)**
> (OpenAPI `1.2.1`). This catalog records feature 001 as it was designed. The current error
> catalog, including `TRANSACTION_BUSY` and `SERVICE_UNAVAILABLE`, and the current
> `INSUFFICIENT_STOCK` semantics are in that document.

**Contract**: [`spec/self-checkout-openapi.yaml`](../../../spec/self-checkout-openapi.yaml)
v1.1.0 | **Index**: [README.md](./README.md)

Every non-2xx response carries an `ApiError` body (FR-029):

```json
{ "error": "MACHINE_READABLE_CODE", "message": "Human-readable explanation." }
```

The `error` value is a stable identifier callers may branch on; `message` is for humans and its
wording is not contractual. The schema is unchanged from contract v1.0.0 — only the set of
documented `error` values for the completion conflict grew.

## Codes

| Code | Status | Raised when | Requirement |
|---|---|---|---|
| `MISSING_STATION_ID` | 400 | `POST /transactions` omits `stationId` or sends it blank | FR-004 |
| `INVALID_LIMIT` | 400 | `GET /analytics/popular-items` receives a `limit` below 1 | FR-027 |
| `TRANSACTION_NOT_FOUND` | 404 | The transaction id in the path does not exist — on scan, complete, or fetch | FR-008, FR-012, FR-014 |
| `SKU_NOT_FOUND` | 404 | A scan names a SKU absent from the catalog | FR-008 |
| `TRANSACTION_NOT_OPEN` | 409 | Scanning into, or completing, a transaction already completed or cancelled | FR-009, FR-012 |
| `EMPTY_BASKET` | 409 | Completing a transaction with no scanned units | FR-012 |
| `INSUFFICIENT_STOCK` | 409 | Completing a transaction in which one or more scanned units hold no claim | FR-013 |

> **Superseded**: scans no longer claim stock. Stock is reserved during completion, and
> `INSUFFICIENT_STOCK` is now returned only when **none** of the remaining units is available.
> Available units are purchased and the rest stay in the open basket. See
> [public-api-amendment.md A2](../../004-domain-services/contracts/public-api-amendment.md#a2-completion-409-insufficient_stock-approved).

## Notes on the 409 family

The three conflict codes share a status code and are distinguished **only** by `error`, which is
why FR-029 requires the insufficient-stock code to be distinct and stable: a caller must be able
to tell "you already finished this" from "the shelf ran out" without parsing prose.

`INSUFFICIENT_STOCK` is the one code that is a normal consequence of concurrency rather than a
client mistake. Its guarantees on refusal (FR-013):

- no receipt is issued,
- no SKU's physical stock changes,
- the transaction is not marked completed.

> **Superseded**: the refusal is no longer terminal, and the transaction is not cancelled. Under
> [public-api-amendment.md A2](../../004-domain-services/contracts/public-api-amendment.md#a2-completion-409-insufficient_stock-approved),
> an `INSUFFICIENT_STOCK` refusal leaves the basket open and unchanged, so the completion may be
> retried later. Scan-time claims no longer exist: stock is reserved during completion.

**The refusal is terminal.** Claims are taken only at scan time, so a unit that failed to
obtain one can never acquire it later, and no operation removes a unit from a basket — meaning
such a transaction can never complete. It is therefore marked `CANCELLED` and its claims are
released back to the pool (R6). A subsequent completion attempt on that id gets
`TRANSACTION_NOT_OPEN`, not `INSUFFICIENT_STOCK` again. The spec defines no retry semantics, and
none are implied.

The load client counts any 409 as an operation error, so an `INSUFFICIENT_STOCK` during a
measured run would show up in the error rate. Measured runs are provisioned with stock to cover
their scan volume precisely so this does not happen and cannot distort week-to-week latency
comparison (SC-002); the code path is instead proven by the targeted oversubscription test in
SC-005.

## Message guidance

Not contractual, but worth keeping consistent for debugging:

- Name the offending identifier — `"Transaction tx-4821 is already completed."`
- For `INSUFFICIENT_STOCK`, name the SKU and the shortfall —
  `"SKU-000123 has 1 unit available but 2 were scanned."`
- Never leak internal state. Claim counts in particular stay out of messages (FR-031).
