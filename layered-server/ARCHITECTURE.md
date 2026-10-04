# Layered Server Architecture

The server has four responsibility layers:

- **API** (`src/selfcheckout/api`): Owns HTTP routing, JSON parsing/encoding, and contract errors.
  It depends only on transaction and analytics services, never on data access.
- **Transactions** (`src/selfcheckout/transactions`): Owns transaction creation, scanning, status,
  completion, and atomic inventory coordination. It depends on data access and analytics.
- **Analytics** (`src/selfcheckout/analytics`): Owns successful-scan recording, the hopping window,
  and popular-item ranking snapshots. It has no transaction-state mutation authority.
- **Data access** (`src/selfcheckout/dataaccess`): Owns in-memory catalog, transactions, inventory,
  and atomic inventory mutation primitives. It depends only on domain values.

## Request traces

`POST /transactions/{id}/complete` flows from `SelfCheckoutRoutes` to
`TransactionService.complete`, then to `SelfCheckoutStore.fulfillAvailable`. Data access computes
the fulfillable quantity for each SKU while holding its inventory lock. The transaction layer
removes fulfilled units, retains unavailable units in the open basket, and returns a
receipt-compatible result. The API layer serializes the optional `unavailableItems` detail and does
not import or call the data-access package.

`GET /analytics/popular-items` flows from `SelfCheckoutRoutes` to
`AnalyticsService.popularItems`. Ranking remains owned by analytics; neither API nor analytics
changes transaction lifecycle state.

## Windowed analytics pipeline

The system has a three-stage pipeline:

```text
ingest (assigns ordered scan sequence numbers)
  -> window (retains the most recent 1,000 scans and emits updates at scan 1 and every 500 scans)
  -> rank-and-publish (counts, sorts, and atomically publishes one complete popularity snapshot)
```

The pipes are unbounded Java `LinkedBlockingQueue` instances. Each stage has one dedicated worker,
so FIFO queue order is the global scan order. `TransactionService` submits a scan only after it has
validated the transaction and added the SKU to the basket; the request thread does not wait for
ranking. Readers receive the previous complete snapshot while a newer one is being calculated.

During orderly shutdown, analytics stops accepting new scans, then forwards a terminal message
through each pipe only after all preceding messages are processed. The application shutdown hook
waits for the workers to drain, so accepted scans are not silently discarded.

## Partial-checkout boundary review

- API imports transaction and analytics services plus domain response values; it has no data-access
  imports.
- Transactions own lifecycle transitions and call the data-access fulfillment boundary; they do not
  encode HTTP responses.
- Analytics records successful scans and calculates rankings only; partial completion does not give
  it transaction-state authority.
- Data access owns stock mutation and returns domain-oriented quantities, never HTTP objects.
