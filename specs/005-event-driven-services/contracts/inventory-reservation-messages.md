# Message Contract: `inventory.commands.v1` and `inventory.replies.v1`

**Commands**: Checkout → Inventory, written to Checkout's `command_outbox` in the transition's
transaction. **Replies**: Inventory → Checkout, written to Inventory's `reply_outbox` in the
reservation transaction. **Java records**: `service-contracts` →
`edu.northeastern.cs6510.contracts.inventory.v1.InventoryCommandV1` / `InventoryReplyV1`. The
existing `ReserveLineV1`, `ReservationLineV1`, `ReservationStateV1`, and `ReservationV1` are
reused as payload parts.

This contract replaces the 004 Inventory internal HTTP API. Command semantics, idempotency by
`attemptId`, tombstones, and conflict codes are unchanged. Only the transport changes.

## Topics

| Property | Commands | Replies |
|---|---|---|
| Key | `attemptId` | `attemptId` |
| Partitions | 12 | 12 |
| Ordering | All commands for one attempt are in send order | All replies for one attempt are in send order |
| Consumer group | `inventory-reservations` (concurrency 6) | `checkout-saga` (concurrency 6) |

## Commands

Headers: envelope with `producer=checkout` and `correlationId=attemptId`.

```json
{
  "schemaVersion": 1,
  "messageId": "0192d0b2-1c3d-7f00-9a11-6b2c3d4e5f60",
  "messageType": "ReserveRequested",
  "attemptId": "0192d0b2-1c3a-7aa0-8b22-1d2e3f405162",
  "transactionId": "7b1e2c94-3f0a-4c1d-9e2b-5a6b7c8d9e0f",
  "lines": [ { "sku": "SKU-000042", "quantity": 3 }, { "sku": "SKU-000107", "quantity": 1 } ]
}
```

| `messageType` | Extra fields | Sent when |
|---|---|---|
| `ReserveRequested` | `transactionId`, `lines` (1–1,000 distinct SKUs, quantity ≥ 1; the frozen snapshot) | The attempt is created (`RESERVING`), and on each resend while `RESERVING` |
| `CommitRequested` | none | The `COMMITTING` transition, and on each resend while `COMMITTING` |
| `CancelRequested` | none | The `CANCELLING` transition, and on each resend while `CANCELLING` |

A resend is a new record with a new `messageId` and an identical body otherwise. The idempotency
key is `attemptId` plus the command, never `messageId`.

## Replies

Headers: envelope with `producer=inventory`, `correlationId=attemptId`, and
`causationId=<command messageId>`.

```json
{
  "schemaVersion": 1,
  "messageId": "0192d0b2-1d10-7c44-8e55-0a1b2c3d4e5f",
  "messageType": "Reserved",
  "attemptId": "0192d0b2-1c3a-7aa0-8b22-1d2e3f405162",
  "command": "ReserveRequested",
  "reservation": {
    "attemptId": "0192d0b2-1c3a-7aa0-8b22-1d2e3f405162",
    "transactionId": "7b1e2c94-3f0a-4c1d-9e2b-5a6b7c8d9e0f",
    "state": "RESERVED",
    "lines": [
      { "sku": "SKU-000042", "requestedQuantity": 3, "reservedQuantity": 2 },
      { "sku": "SKU-000107", "requestedQuantity": 1, "reservedQuantity": 1 }
    ],
    "createdAt": "2026-11-02T15:04:05.123456Z",
    "resolvedAt": null
  },
  "error": null
}
```

| `messageType` | `reservation.state` | Meaning |
|---|---|---|
| `Reserved` | `RESERVED` | At least one unit held. `reservedQuantity` may be below `requestedQuantity` (partial) |
| `Released` | `RELEASED` | Nothing available at reserve time, or released by cancel |
| `Cancelled` | `CANCELLED` | Cancel arrived first and a tombstone was written. A later reserve holds nothing |
| `Committed` | `COMMITTED` | The sale is final. Stock is queued for `SaleApplier` (004) |
| `Conflict` | stored state, or `null` when none | `error` = `{ "code": …, "message": … }`, with a code from the table below |

`command` names the command being answered, so Checkout can tell a stale reply to an earlier
command from a reply to the current one.

| Conflict code | When |
|---|---|
| `INVALID_RESERVATION_REQUEST` | Reserve lines fail validation (004 rules) |
| `RESERVATION_MISMATCH` | A repeated reserve has different lines from the stored reservation |
| `RESERVATION_NOT_FOUND` | Commit for an unknown `attemptId` |
| `RESERVATION_NOT_COMMITTABLE` | Commit for a `RELEASED` or `CANCELLED` reservation |
| `RESERVATION_COMMITTED` | Cancel for a `COMMITTED` reservation |

## Guarantees

**Inventory**:
1. Each command is one local transaction: the reservation change (or none, for a repeat) and
   exactly one `reply_outbox` row. The offset is committed after that transaction.
2. Repeats return the stored outcome and apply no second stock effect (004).
3. Commit and cancel for one attempt are mutually exclusive (004 lock order).

**Checkout**:
1. A reply is applied only as the conditional transition listed in
   [data-model.md](../data-model.md). Others are ignored and counted
   (`checkout.saga.reply.ignored{reason}`).
2. `Conflict`, or `Reserved` lines that don't match the frozen snapshot, parks the attempt with
   `needs_attention`.
3. `CommitRequested` is sent only after `COMMITTING` is durable. `CancelRequested` is sent only
   from `RESERVING` (by recovery, after `cancel-after`), never after `COMMITTING`.
4. A missing reply leads to a resend of the same command with backoff (data-model rule 3),
   never to compensation on its own.

## Timing (research R5)

| Budget | Value |
|---|---|
| Public completion deadline | 5 s (unchanged). Then `503 SERVICE_UNAVAILABLE`, and the attempt continues |
| First resend | 1 s after the transition, if no reply has been applied |
| Resend backoff | 250 ms doubling, capped at 5 s |
| Attention threshold | 20 sends of one state's command |
| Cancel after | 30 s in `RESERVING` |
| Design target | Complete p95 ≤ 100 ms at 100 stations (reported as measured) |

## Fixtures

`fixtures/messages/`: `reserve-requested-v1.json`, `commit-requested-v1.json`,
`cancel-requested-v1.json`, `reply-reserved-partial-v1.json`, `reply-released-v1.json`,
`reply-cancelled-v1.json`, `reply-committed-v1.json`, `reply-conflict-v1.json`. The 004 fixtures
`reservation-v1.json` and `reserve-request-v1.json` remain as the embedded payload shapes.
