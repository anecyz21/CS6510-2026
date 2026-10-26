# Architecture: independently deployable domain services with private stores

> **Superseded guidance.** The previous version of this file described *database-only
> coordination*: one shared MySQL instance, cross-schema reads and grants, and completion as one
> stored-procedure call. That design is retired. Public behavior changes are recorded in
> [`public-api-amendment.md`](../../specs/004-domain-services/contracts/public-api-amendment.md)
> (research R19). The pre-migration system is kept at tag `baseline/service-based-v1`.

Each domain service owns one MySQL instance that no other service can reach. Services talk only
through versioned internal HTTP contracts and one Kafka topic. The gateway translates the public
contract and has no data store. Design decisions are in
[`research.md`](../../specs/004-domain-services/research.md); entities and state machines are in
[`data-model.md`](../../specs/004-domain-services/data-model.md).

## Deployables and ports

| Deployable | Image or build | Compose service | Port inside the network | Host port |
|---|---|---|---|---|
| API gateway | `../api-gateway` | `gateway` | 8080 | `8080` (public) |
| Catalog | `../catalog-service` | `catalog` | 8081 | `127.0.0.1:8081` (operator only) |
| Checkout | `../checkout-service` | `checkout` | 8082 | `127.0.0.1:8082` (operator only) |
| Inventory | `../inventory-service` | `inventory` | 8083 | `127.0.0.1:8083` (operator only) |
| Analytics | `../analytics-service` | `analytics` | 8084 | `127.0.0.1:8084` (operator only) |
| Catalog store | `mysql:8.4` | `catalog-db` | 3306 | `127.0.0.1:3311` |
| Checkout store | `mysql:8.4` | `checkout-db` | 3306 | `127.0.0.1:3312` |
| Inventory store | `mysql:8.4` | `inventory-db` | 3306 | `127.0.0.1:3313` |
| Analytics store | `mysql:8.4` | `analytics-db` | 3306 | `127.0.0.1:3314` |
| Broker | `apache/kafka:3.8.1` (KRaft, one node) | `kafka` | 29092 | `127.0.0.1:9092` |

- **Host ports:** the application host ports can be changed with `CATALOG_HOST_PORT`,
  `CHECKOUT_HOST_PORT`, `INVENTORY_HOST_PORT` and `ANALYTICS_HOST_PORT`.
- **Operator ports** (`127.0.0.1:8081–8084`) serve actuator health and metrics and
  `/internal/v1/ops/**` for reconciliation and diagnostics. They are bound to the loopback
  interface only, and the gateway never routes `/internal/**`.
- **Readiness** (`/actuator/health/readiness`) covers only the service's own database. Each service
  starts and becomes ready while every other service is stopped (US3-1, research R17).
- **Fault injection:** `docker-compose.faults.yml` adds Toxiproxy between Checkout and Inventory
  and enables Checkout's fault endpoint. It is used only by the V7 recovery tests, never by
  measured runs.

## Data ownership and permissions

| Owner | Instance | Database | Account | Can reach |
|---|---|---|---|---|
| Catalog | `catalog-db` | `catalog` | `catalog_service` | its own database only |
| Checkout | `checkout-db` | `checkout` | `checkout_service` | its own database only |
| Inventory | `inventory-db` | `inventory` | `inventory_service` | its own database only |
| Analytics | `analytics-db` | `analytics` | `analytics_service` | its own database only |
| Gateway | — | — | — | no datasource, no JDBC, Flyway or Kafka dependency |

- **No cross grants exist.** Each account is created by its own instance's
  `MYSQL_DATABASE`/`MYSQL_USER` settings, so it holds privileges on that database alone, and it has
  no account on any other instance.
- **Removed objects:** the former cross-store views (`active_items`, `low_stock`,
  `published_popularity`, the `accepted_scan_outbox` view) and the `fulfill_checkout` procedure no
  longer exist.
- **Credentials** come only from `services.env`, which is never committed (`services.env.example`
  is the template), and each service has its own password (FR-028).
- **Tests:** `PrivateStoreAccessTest` proves the isolation. It tries every account against every
  instance, checks that each owner sees only its own database, and looks for the old cross-store
  objects. `ModuleBoundaryTest` checks the code statically: domain modules share only
  `service-contracts`, no service SQL names another schema, and the gateway holds routing only.
- **Derived copies:** Checkout's catalog projection and the name projections in Inventory and
  Analytics are copies of Catalog's data, owned by the copying service and rebuilt from Catalog's
  internal API. They are never authoritative.

## Internal contracts

All are listed in [`contracts/`](../../specs/004-domain-services/contracts/README.md), with shared
rules for idempotency keys, versioning, timeouts and failure semantics.

| Contract | Provider → consumer |
|---|---|
| [Catalog internal API](../../specs/004-domain-services/contracts/catalog-internal-api.md) (`/internal/v1/catalog-version`, `/internal/v1/products`) | Catalog → Checkout, Inventory, Analytics |
| [Inventory internal API](../../specs/004-domain-services/contracts/inventory-internal-api.md) (reserve, commit, cancel, get, keyed by `attemptId`) | Inventory → Checkout |
| [Operations API](../../specs/004-domain-services/contracts/checkout-operations-api.md) (`/internal/v1/ops/**`) | Checkout, Inventory, Analytics → operators and tests |
| [Accepted-scan event](../../specs/004-domain-services/contracts/accepted-scan-event.md) (`checkout.accepted-scans.v1`) | Checkout → Analytics |
| [Public API amendment](../../specs/004-domain-services/contracts/public-api-amendment.md) (OpenAPI 1.2.1, error catalog) | Gateway → clients |

Canonical JSON fixtures live in `../service-contracts/src/test/resources/fixtures/`. Provider and
consumer tests in each module validate against them.

## Completion: saga and recovery

Completion is a saga of local transactions, coordinated by Checkout and keyed by a durable
`attemptId` (research R8). The state machine and per-transition effects are in
[`data-model.md`, completion attempt state machine](../../specs/004-domain-services/data-model.md#completion-attempt-state-machine).

1. **Freeze** (Checkout transaction): snapshot the basket into an attempt in `RESERVING` and set
   `active_attempt_id`. While the flag is set, scans answer `409 TRANSACTION_BUSY`, and a second
   completion call joins the running attempt.
2. **Reserve** (Inventory transaction): grant what is available per line. The grants are held in
   `reserved_stock`; physical stock does not change.
3. **Prepare** (Checkout transaction): store the reserved quantities as the receipt intent, then
   move to `COMMITTING`. If nothing was reserved, the attempt is `REJECTED` and the call answers
   `409 INSUFFICIENT_STOCK` with the basket unchanged.
4. **Commit** (Inventory transaction): mark the reservation `COMMITTED` and queue its lines in
   `sale_queue`. Inventory's `SaleApplier` moves the quantities from reserved to physical stock
   within about 100 ms, with one update per SKU per batch, and writes the `SALE` movements.
5. **Finalize** (Checkout transaction): write the per-attempt receipt, remove the purchased units
   from the basket, set `COMPLETED` if the basket is empty, and clear the flag.

**Recovery rules** (research R9, Checkout `checkout.recovery.*`):
- A recovery worker scans unresolved attempts every 1 s. It holds a 10 s lease per attempt, so
  exactly one worker drives an attempt at a time.
- `RESERVING` attempts are retried with backoff (250 ms doubling to 5 s). An attempt that is still
  unconfirmed after 30 s is cancelled: Inventory's cancel releases the grant or writes a tombstone,
  so a late reserve has no effect.
- `COMMITTING` is forward-only and retried until Inventory confirms. After 20 failures the attempt
  is marked `needs_attention` but is never cancelled.
- Every Inventory command is idempotent by `attemptId`, so a repeat after a lost response has one
  stock effect.
- **Deadline:** a public completion waits at most 5 s. Past that it answers
  `503 SERVICE_UNAVAILABLE`, and the attempt continues in the background (amendment A4).
- **Inventory calls** use a 300 ms connect timeout, a 1.5 s read timeout and 3 in-request retries.

**Inventory locking:**
- Reserve and cancel lock position rows one at a time, by conditional updates in descending SKU
  order, with no up-front `FOR UPDATE` read.
- Commit locks no position row at all.
- This removed the hot-SKU lock queue measured in
  [`reports/redesign/exploratory/`](reports/redesign/exploratory/inventory-locking/NOTES.md).

## Accepted scans: outbox, relay and Kafka

1. **Outbox:** a scan's basket change and its `accepted_scan_outbox` row (with a UUIDv7 `eventId`)
   commit in the same Checkout transaction (FR-020). A rejected scan writes nothing.
2. **Relay:** `OutboxRelay` holds the MySQL named lock `checkout.scan-relay` on a dedicated
   connection, so one relay runs per Checkout database. It sends unpublished rows in `outbox_id`
   order to `checkout.accepted-scans.v1`, a single-partition topic with idempotent producer,
   `acks=all` and 7-day retention. It marks a row `published_at` only after the broker acknowledges
   it.
3. **Kafka down:** scans keep succeeding, and the rows wait in the outbox. A crash between
   acknowledgment and marking republishes the same `eventId`. Published rows are purged after
   24 h.
4. **Consumer:** Analytics consumes in batches (group `analytics-scan-ingest`, manual
   acknowledgment). Offsets are committed only after the batch's local transaction commits, and a
   failed batch is sought back and redelivered.

## Analytics pipeline: `ingest → window → rank-and-publish`

Each batch runs the three filters in this order, inside one Analytics transaction (constitution
III, research R5):

1. **Ingest:** records each `eventId` once. Under a lock on `ingest_state`, it assigns the next
   canonical `sequence_number`. Duplicates and replays change nothing. Unreadable values and newer
   schema versions go to `ingest_rejections`.
2. **Window:** for every boundary the batch crosses (sequence 1, then every 500), it emits the
   window `[max(1, end − 999), end]`, which holds the latest 1,000 scans.
3. **Rank and publish:**
   - ranks the window by scan count descending, then SKU ascending, using projected catalog names;
   - writes the snapshot rows;
   - moves the `published_snapshot` pointer.

   Readers see a complete old or new ranking, never a mix. A missing name fails the batch, which is
   retried rather than publishing a SKU as the name.

`GET /analytics/popular-items` reads the pointer and its snapshot in one read-only transaction.
Before the first publication it returns an empty ranking. `GET /internal/v1/ops/ingest-status`
reports ingested count, last sequence, current window and consumer lag.

## Catalog projection policy

- **Checkout's priced projection** (research R6):
  - loaded at startup and whenever Catalog's `catalogVersion` changes, by paging
    `/internal/v1/products` at one version and replacing the copy atomically;
  - checked every 15 s and considered current while the last successful check is at most 60 s
    old;
  - a SKU missing from a *current* projection is `404 SKU_NOT_FOUND`;
  - when the projection is stale or empty, Checkout asks Catalog directly (300 ms). If Catalog is
    unreachable, the scan answers `503 SERVICE_UNAVAILABLE`, never "not found".
- **Name projections in Inventory and Analytics** (research R7) follow the same load and freshness
  rules but store names only.
  - Low-stock answers `503` when an alerted SKU has no name.
  - Analytics delays publication until the name is known.
  - Deleting a projection's rows makes it forget the names and reload.
- Health reports each projection's `state` (`EMPTY`, `CURRENT`, `STALE`) as detail, outside
  readiness.
