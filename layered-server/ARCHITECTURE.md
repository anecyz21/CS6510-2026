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
`TransactionService.complete`, then to `SelfCheckoutStore.decrementIfAvailable`. The API layer
does not import or call the data-access package.

`GET /analytics/popular-items` flows from `SelfCheckoutRoutes` to
`AnalyticsService.popularItems`. Ranking remains owned by analytics; neither API nor analytics
changes transaction lifecycle state.
