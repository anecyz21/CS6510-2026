# Implementation Plan: Database-Coordinated Domain Services

**Branch**: `004-domain-services` | **Date**: 2026-10-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-domain-services/spec.md`

## Summary

Replace the current single in-memory layered server with independently deployable Catalog,
Checkout, Inventory, and Analytics services, plus a thin API gateway that preserves the shared
public contract. Services share one MySQL server but own separate schemas and database
interfaces. Domain services never call one another: Checkout writes accepted scans to its outbox,
Analytics reads them and records an idempotent checkpoint, and Checkout invokes the Inventory
fulfillment stored procedure within its database transaction.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.4.3, Spring MVC, Spring JDBC, MySQL Connector/J,
Flyway MySQL, JUnit 5

**Storage**: One MySQL 8 server with `catalog`, `checkout`, `inventory`, and `analytics`
schemas; Flyway migrations owned by the service that owns each schema

**Testing**: Maven unit, contract, and integration tests; Docker Compose deployment checks;
unchanged supplied load client; SQL reconciliation after stress workload

**Target Platform**: Local developer workstation and container-capable environment with JDK 21
and MySQL 8

**Project Type**: Multi-module web-service system with independently executable service JARs and
an API gateway

**Performance Goals**: Preserve contract-compatible synchronous checkout at the default
10-station/60-second workload; record normal and 200-station/180-second stress results with
their environment and data-provisioning settings

**Constraints**: Preserve the existing OpenAPI contract and unmodified load client; deploy
Catalog, Checkout, Inventory, and Analytics separately; use one centralized database server;
prohibit direct domain-service communication; use least-privilege database accounts; retain the
`ingest → window → rank-and-publish` analytics behavior; preserve atomic partial completion

**Scale/Scope**: Four domain services, one public gateway, four owned schemas, a 2,000-item
catalog, one ordered accepted-scan stream, and normal/stress workload evidence

## Constitution Check

*GATE: Passed before Phase 0 research. Re-checked after Phase 1 design: passed.*

| Constitution requirement | Design response | Status |
| --- | --- | --- |
| Stable public contract | Gateway retains the seven documented routes and response shapes. | Pass |
| Unchanged measuring harness | Load client is used only through its documented flags. | Pass |
| Independent domain services | Catalog, Checkout, Inventory, and Analytics are separate Maven modules and executable JARs. | Pass |
| No direct domain-service calls | Services share no domain clients; only the gateway makes HTTP calls, while domains coordinate through database interfaces. | Pass |
| Centralized database ownership | Each domain owns one schema, migration set, account, and documented read/execute grants. | Pass |
| Atomic checkout and inventory | Checkout calls an Inventory-owned stored procedure inside its transaction; direct inventory writes are denied. | Pass |
| Durable analytics work | Checkout atomically persists accepted scans; Analytics uses an idempotent checkpoint and snapshots. | Pass |
| Evidence and deployability | Compose validation, readiness checks, contract tests, reconciliation, and load reports are required. | Pass |

## Project Structure

### Documentation (this feature)

```text
specs/004-domain-services/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── public-and-database-boundaries.md
└── tasks.md
```

### Source Code (repository root)

```text
pom.xml
domain-services/
├── shared-db/
├── catalog-service/
│   ├── src/main/java/
│   ├── src/main/resources/db/migration/
│   └── src/test/java/
├── checkout-service/
│   ├── src/main/java/
│   ├── src/main/resources/db/migration/
│   └── src/test/java/
├── inventory-service/
│   ├── src/main/java/
│   ├── src/main/resources/db/migration/
│   └── src/test/java/
├── analytics-service/
│   ├── src/main/java/
│   ├── src/main/resources/db/migration/
│   └── src/test/java/
├── api-gateway/
├── deployment/
│   ├── docker-compose.yml
│   ├── mysql/init/
│   └── reports/
└── integration-tests/
    ├── src/test/java/
    └── scripts/
```

**Structure Decision**: Use a Maven reactor with one executable Spring Boot JAR per domain
service and one executable gateway JAR. `shared-db` is a constrained infrastructure module, not
a domain library. Docker Compose starts the centralized MySQL server and the five deployables for
repeatable integration and workload testing.

## Complexity Tracking

No constitution violations require justification. Five deployables are required: the four named
domain boundaries plus the permitted transport-only gateway.
