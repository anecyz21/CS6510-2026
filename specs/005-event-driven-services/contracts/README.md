# Contracts: Event-Driven Communication Between Checkout Microservices

All communication between Catalog, Checkout, Inventory, and Analytics uses these Kafka topics
(constitution v4.0.0, Principle III). Java records live in `service-contracts`, and canonical
JSON fixtures live in `domain-services/service-contracts/src/test/resources/fixtures/messages/`.
Producer and consumer tests validate against the same fixtures.

| Contract | Topic(s) | Publisher → Consumer(s) | Version |
|---|---|---|---|
| [catalog-product-event.md](catalog-product-event.md) | `catalog.products.v1` | Catalog → Checkout, Inventory, Analytics | v1 (new) |
| [inventory-reservation-messages.md](inventory-reservation-messages.md) | `inventory.commands.v1`, `inventory.replies.v1` | Checkout → Inventory, Inventory → Checkout | v1 (new; replaces the 004 Inventory internal HTTP API) |
| [accepted-scan-event.md](accepted-scan-event.md) | `checkout.accepted-scans.v1` | Checkout → Analytics | v1 (004, additive headers) |

## Topic catalog (FR-021)

| Topic | Owner (only publisher) | Consumers (group) | Key | Partitions | Cleanup / retention | Schema versions |
|---|---|---|---|---|---|---|
| `catalog.products.v1` | Catalog | Checkout (`checkout-catalog-projection`), Inventory (`inventory-catalog-projection`), Analytics (`analytics-catalog-projection`) | `sku` | 1 | `compact`, `min.compaction.lag.ms=0` | 1 |
| `inventory.commands.v1` | Checkout | Inventory (`inventory-reservations`) | `attemptId` | 12 | `delete`, 7 d | 1 |
| `inventory.replies.v1` | Inventory | Checkout (`checkout-saga`) | `attemptId` | 12 | `delete`, 7 d | 1 |
| `checkout.accepted-scans.v1` | Checkout | Analytics (`analytics-scan-ingest`) | `transactionId` | 1 | `delete`, 7 d | 1 |
| `checkout.dlt.v1` | Checkout | Operators | original key | 1 | `delete`, 14 d | as the original record |
| `inventory.dlt.v1` | Inventory | Operators | original key | 1 | `delete`, 14 d | as the original record |
| `analytics.dlt.v1` | Analytics | Operators | original key | 1 | `delete`, 14 d | as the original record |

Replication factor 1 and `min.insync.replicas` 1 on the single local broker. Durability comes from
the broker's log volume plus each producer's outbox. The owner creates each topic at startup
through a `NewTopic` bean, and consumers wait for a topic that does not exist yet.

## Envelope (every record)

| Header | Value | Required on |
|---|---|---|
| `messageId` | UUIDv7, unique per send | all |
| `messageType` | e.g. `ProductChanged`, `ReserveRequested`, `Reserved`, `AcceptedScan` | all |
| `schemaVersion` | integer, `1` | all |
| `producer` | `catalog` \| `checkout` \| `inventory` | all |
| `occurredAt` | ISO-8601 UTC with microseconds | all |
| `correlationId` | `attemptId` | commands, replies |
| `causationId` | `messageId` of the command being answered | replies |

- **Body**: UTF-8 JSON that repeats `schemaVersion`, `messageId`, and `messageType`.
- **Evolution**: consumers ignore unknown fields. Additive changes keep the topic version; a
  breaking change creates `…v2`, and both versions are published until every consumer has
  moved.
- **Acknowledgment**: consumers commit offsets only after their local transaction commits
  (`AckMode.RECORD`, or per batch for projections, which store their own offset).

## Retries and dead-letter topics (FR-017)

- **Retries**: 4 attempts with backoff from 200 ms doubling to a 5 s cap. After the last
  attempt the record goes to the consuming service's dead-letter topic, unchanged. The
  recoverer adds headers `dlt-original-topic`, `dlt-original-partition`, `dlt-original-offset`,
  `dlt-exception-class`, and `dlt-exception-message`.
- **Not retried**: a record whose body cannot be parsed, or whose `messageType` is unknown,
  goes straight to the dead-letter topic.
- **Healing errors**: database unavailability and lock timeouts are retried, and the listener
  pauses on them instead of dead-lettering.
- **Visibility**: each service exposes the `messaging.dlt.published{topic}` counter and a
  `deadLetters` entry in its health detail. `reconcile.sh` reports counts per dead-letter topic.

## What stays HTTP

These are not communication between domain services, so they may remain HTTP (spec
Assumptions):

| Path | Caller → Owner | Purpose |
|---|---|---|
| The seven public routes ([004 public-api-amendment.md](../../004-domain-services/contracts/public-api-amendment.md)) | Gateway → the one owning service | Public API, unchanged |
| `/internal/v1/ops/**` on each service ([004 checkout-operations-api.md](../../004-domain-services/contracts/checkout-operations-api.md)) | Operators and tests → one service | Attempt inspection and retry, reconciliation export, projection rebuild, catalog republish |
| Inventory test provisioning (004) | Tests → Inventory | Seed stock for measured runs and shortage tests |
| `/actuator/**` | Compose health checks and operators → one service | Health, readiness, metrics |

## Removed HTTP contracts

| 004 contract | Replaced by |
|---|---|
| Catalog internal API v1 (`/internal/v1/catalog-version`, `/internal/v1/products`, `/internal/v1/products/{sku}`) | `catalog.products.v1` |
| Inventory internal API v1 reservation routes (`PUT /internal/v1/reservations/{id}`, `…/commit`, `…/cancel`, `GET …/{id}`) | `inventory.commands.v1` and `inventory.replies.v1` |

The public low-stock route `GET /inventory/low-stock`, which Inventory owns, is unchanged.
