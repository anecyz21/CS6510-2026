# Checkout Operations API v1 (operator visibility)

**Provider**: Checkout service | **Consumers**: operators, integration tests and reconciliation
scripts | **Transport**: HTTP JSON on the internal network (port 8082). **Not routed by the
gateway.**

Supports FR-018 ("expose unresolved attempts to operators") and SC-004. All endpoints are
read-only except `retry`, which only makes an attempt immediately eligible for the recovery worker
and never changes the saga's decisions.

## `GET /internal/v1/ops/completion-attempts?state={unresolved|needsAttention|all}&limit={n}`

Default `state=unresolved`, which means `RESERVING`, `COMMITTING` or `CANCELLING`. `limit`
defaults to 100, maximum 1000.

```json
200 {
  "generatedAt": "2026-10-24T12:05:00Z",
  "count": 1,
  "attempts": [
    {
      "attemptId": "0192c3f4-…",
      "transactionId": "7b1e…",
      "state": "COMMITTING",
      "createdAt": "2026-10-24T12:04:10Z",
      "updatedAt": "2026-10-24T12:04:55Z",
      "stepFailures": 9,
      "nextRetryAt": "2026-10-24T12:05:05Z",
      "needsAttention": false,
      "lastError": "inventory: connect timed out",
      "lines": [ { "sku": "SKU-000001", "requestedQuantity": 3, "reservedQuantity": 2 } ]
    }
  ]
}
```

## `GET /internal/v1/ops/completion-attempts/{attemptId}`

`200` with one attempt as above, or `404`.

## `POST /internal/v1/ops/completion-attempts/{attemptId}/retry`

Sets `next_retry_at = now` and clears `needs_attention`. Returns `202`. Allowed only for
non-terminal states; otherwise `409 ATTEMPT_TERMINAL`.

## `GET /internal/v1/ops/reconciliation-export`

Checkout's half of the settled-state reconciliation
([data-model.md](../data-model.md#settled-state-reconciliation-fr-019-sc-005)):

```json
200 {
  "unresolvedAttempts": 0,
  "activeAttemptFlags": 0,
  "receiptQuantityBySku": { "SKU-000001": 1234 },
  "finalizedAttemptIds": ["…"],
  "outboxPending": 0,
  "outboxTotal": 281724
}
```

Inventory exposes its half at `GET /internal/v1/ops/reconciliation-export`, and Analytics at
`GET /internal/v1/ops/ingest-status`, with the same read-only rules:

- Inventory returns `initialStock`/`physicalStock`/`reservedStock` per SKU,
  `openReservations` and `committedAttemptIds`.
- Analytics returns `ingestedCount`, `lastSequence`, `currentWindowEnd`, `consumerLag` and
  `rejected`.

`deployment/scripts/reconcile.sh` combines the three exports. No script reads another service's
database.

## Metrics (actuator `/actuator/metrics`, `/actuator/prometheus` if enabled)

| Metric | Meaning |
|---|---|
| `checkout.completion.attempts{state}` | Gauge of attempts per state |
| `checkout.completion.outcome{result=receipt\|insufficient\|unavailable\|joined}` | Public completion outcomes |
| `checkout.recovery.steps{step,result}` | Recovery step successes and failures |
| `checkout.outbox.pending` | Unpublished outbox rows |
| `checkout.db.retries{operation}` | Deadlock and lock-wait retries |
| `hikaricp.connections.*` | Pool usage, pending and acquire time |
