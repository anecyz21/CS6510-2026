# Event Contract: `checkout.accepted-scans.v1`

**Producer**: Checkout (transactional outbox + relay) | **Consumer**: Analytics (group
`analytics-scan-ingest`) | **Broker**: Apache Kafka 3.8.1 | **Java record**: `service-contracts`
→ `edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1`

## Topic

| Property | Value | Why |
|---|---|---|
| Name | `checkout.accepted-scans.v1` | The version is in the name. A breaking change creates `…v2`, and both coexist during migration |
| Partitions | 1 | One total order = the canonical input order for windows (R3, R5) |
| Replication / `min.insync.replicas` | 1 / 1 | Single local broker. Durability is from the broker log volume and the outbox |
| Retention | 7 days | Covers outage and replay tests |
| Cleanup | `delete` | |

## Message

- **Key**: `transactionId` (UTF-8). It has no ordering effect with one partition and is reserved
  for future partitioning.
- **Headers**: `eventId`, `eventType=AcceptedScan`, `schemaVersion=1`.
- **Value** (UTF-8 JSON):

```json
{
  "schemaVersion": 1,
  "eventId": "0192c3f4-8b7a-7cc1-9a51-2f0e5d6c1a90",
  "eventType": "AcceptedScan",
  "transactionId": "7b1e2c94-…",
  "stationId": "station-17",
  "sku": "SKU-000001",
  "acceptedAt": "2026-10-24T12:00:00.123456Z",
  "outboxId": 18342
}
```

| Field | Rules |
|---|---|
| `eventId` | UUIDv7 created in the scan's local transaction. **Unique per accepted scan; the deduplication key** |
| `sku` | The scanned SKU as accepted (always valid at accept time) |
| `acceptedAt` | Commit-time timestamp. Informational only, **not** an ordering key |
| `outboxId` | Producer-local diagnostic. Consumers must not depend on its contiguity |

Only **accepted** scans are published. A rejected scan (`404`, `409 TRANSACTION_NOT_OPEN`,
`409 TRANSACTION_BUSY`, `503`) writes no outbox row and produces no event.

## Producer guarantees (Checkout)

1. **Atomic with the scan**: the outbox row commits in the same local transaction as the basket
   upsert. If the scan commits, the event will be published (FR-020).
2. **At-least-once publication**: the relay sends unpublished rows in `outbox_id` order in batches
   of up to 500. It uses `enable.idempotence=true` and `acks=all`, and marks rows `published_at`
   only after broker acknowledgment. A crash between acknowledgment and marking republishes those
   rows, giving the same `eventId` at a later offset.
3. **No starvation**: each pass selects only unpublished rows, so progress always advances past
   published rows. Rows that commit late are picked up on a later pass.
4. **Single relay**: guarded by MySQL `GET_LOCK('checkout.scan-relay', 0)` on Checkout's own
   database.
5. **Outage behavior**: if the broker is unavailable, scans keep succeeding. Rows remain pending,
   and the pending count is exposed as metric `checkout.outbox.pending` and in health detail
   (FR-025). Relay retries every 200 ms → backoff cap 5 s.

## Consumer guarantees (Analytics)

1. **Manual acknowledgment after durable processing**: auto-commit is disabled. Offsets are
   committed only after the batch's local transaction commits (FR-022).
2. **Idempotent effect**: within one local transaction, the consumer runs
   `INSERT IGNORE ingested_scans(event_id, …)`. A new row receives
   `sequence_number = ingest_state.last_sequence + 1` under a row lock. A duplicate `eventId` has
   no effect.
3. **Canonical order**: the first-ingest sequence follows partition order. It is immutable once
   committed, so replay (offset reset) and duplicates cannot change sequence numbers, window
   boundaries or ranks (FR-024, SC-006).
4. **Windows**: every crossed boundary (sequence 1, then each multiple of 500) publishes the
   window `[max(1, end−999), end]` and flips `published_snapshot` in the same transaction.
5. **Poison messages**: an unparseable value or `schemaVersion` > 1 is written to
   `ingest_rejections(offset, reason, raw)` and acknowledged. It is counted in metric
   `analytics.ingest.rejected` and never silently dropped.
6. **Restart and shutdown**: the consumer resumes from committed offsets. Graceful shutdown
   finishes the in-flight batch transaction. Anything not committed is redelivered.

## Compatibility

- Within v1, producers may add optional fields, and consumers ignore unknown fields.
- Removing or renaming a field, changing a meaning, or changing the ordering contract requires
  `checkout.accepted-scans.v2`. During migration the producer dual-writes v1 and v2.
- The consumer fixture test parses `service-contracts/src/test/resources/fixtures/accepted-scan-v1.json`,
  and the producer test serializes to the same fixture. Both must pass before deployment (R16).
