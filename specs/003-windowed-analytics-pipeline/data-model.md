# Data Model: Windowed Analytics Pipeline

## ScanEvent

| Field | Description | Validation |
| --- | --- | --- |
| `sequence` | Global position assigned by `ingest`. | Positive and unique in a server run. |
| `sku` | Successfully accepted catalog item identifier. | Nonblank and valid. |
| `acceptedAt` | Analytics acceptance time. | Present; ordering is by sequence. |

**Lifecycle**: accepted → sequenced → retained or evicted by `window` → included in a completed
window update when its refresh boundary occurs.

## AnalyticsWindow and WindowUpdate

`window` alone owns the mutable ordered window. It retains at most 1,000 scan events, advances the
end for every input, evicts from the oldest end, and sends an immutable `WindowUpdate` at scan 1
and each 500th scan. An update contains the copied ordered events, `windowStart`, `windowEnd`,
`windowSize`, and `slideInterval`.

## AnalyticsSnapshot

The latest complete popularity result readable by requests.

| Field | Description | Validation |
| --- | --- | --- |
| `windowSize` / `slideInterval` | Running configuration. | Always present and positive. |
| `windowStart` / `windowEnd` | Bounds of the ranked window. | Both zero only before the first update. |
| `computedAt` | Ranking completion time. | Present. |
| `items` | Immutable rankings. | Count descending, SKU ascending, consecutive ranks. |

**Lifecycle**: initial empty snapshot → atomically replaced only after one full `WindowUpdate` is
ranked. Readers can apply a result limit but cannot observe or modify an in-progress snapshot.

## TerminalMessage

A control message appended only after intake closes. Each filter forwards it only after processing
every preceding ordinary message, then terminates. This is the ordered shutdown-drain protocol.

## Relationships

```text
ScanEvent -> AnalyticsWindow -> WindowUpdate -> AnalyticsSnapshot
TerminalMessage -> orderly termination of the next filter
```
