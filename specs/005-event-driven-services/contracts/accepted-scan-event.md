# Event Contract: `checkout.accepted-scans.v1` (005 delta)

The full contract is unchanged from
[004 accepted-scan-event.md](../../004-domain-services/contracts/accepted-scan-event.md):
topic, single partition, body, producer guarantees, Analytics ingest, deduplication by `eventId`,
and canonical ordering.

## Additive changes in 005

| Change | Compatibility |
|---|---|
| New headers `producer=checkout` and `occurredAt` (= `acceptedAt`), joining the existing `eventId`, `eventType`, and `schemaVersion` headers, to match the shared envelope ([README.md](README.md)) | Additive. Analytics 004 ignores unknown headers. `eventId` remains the deduplication key, and the envelope's `messageId` header carries the same value |
| Analytics' listener gets the shared `DefaultErrorHandler` and dead-letters to `analytics.dlt.v1` instead of logging to `ingest_rejections` only | Behavior on valid records is unchanged. A record that cannot be parsed is now visible in a topic as well as in the table |
| Product names in `GET /analytics/popular-items` come from the event-built projection ([catalog-product-event.md](catalog-product-event.md)) instead of the Catalog HTTP API | Public response unchanged |

The body and `schemaVersion` stay `1`. No `…v2` topic is created.
