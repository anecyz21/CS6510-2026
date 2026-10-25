# Contracts: Independently Deployable Checkout Microservices

| Contract | Kind | Provider → Consumer(s) | Version |
|---|---|---|---|
| [public-api-amendment.md](public-api-amendment.md) | Public HTTP (OpenAPI amendment A1–A6 + error catalog) | Gateway → load client and users | OpenAPI `1.2.0` |
| [catalog-internal-api.md](catalog-internal-api.md) | Internal HTTP | Catalog → Checkout, Inventory, Analytics | `/internal/v1` |
| [inventory-internal-api.md](inventory-internal-api.md) | Internal HTTP (reservation commands) | Inventory → Checkout | `/internal/v1` |
| [checkout-operations-api.md](checkout-operations-api.md) | Internal HTTP (operator, reconciliation) | Checkout/Inventory/Analytics → operators, tests | `/internal/v1/ops` |
| [accepted-scan-event.md](accepted-scan-event.md) | Kafka event | Checkout → Analytics | `checkout.accepted-scans.v1` |

## Rules shared by every internal contract (FR-029, research R16)

- **Identity**: commands carry a stable idempotency key (`attemptId`) and events carry `eventId`.
  Repeats have one business effect.
- **Versioning**: the version lives in the path or topic name. Additive fields are allowed within
  a version, and consumers ignore unknown fields. A breaking change gets a new version served in
  parallel until every consumer has moved. Services deploy independently for compatible changes.
- **Timeouts**: every synchronous call has explicit connect and read timeouts and bounded retries
  (research R9). Callers never hold a database transaction or row lock while waiting.
- **Failure semantics**: a timeout is an *unknown* outcome, and the caller repeats or queries the
  same operation. Dependency failure is never reported as "not found" or as success.
- **Fixtures**: canonical JSON examples live in
  `domain-services/service-contracts/src/test/resources/fixtures/`. Provider and consumer tests
  both validate against them, so an incompatible change fails the build.
- **Network exposure**: `/internal/**` paths are reachable only on the Compose internal network
  and are never routed by the gateway.
