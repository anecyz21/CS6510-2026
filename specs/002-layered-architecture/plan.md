# Implementation Plan: Layered Server Architecture

**Branch**: `002-layered-architecture` | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-layered-architecture/spec.md`

## Summary

Build this week's self-checkout server as an independently runnable Java 21 service with four
explicit layers: API, transactions, analytics, and data access. Preserve the shared OpenAPI
contract and its synchronous behavior. The transactions layer owns checkout and atomic inventory
completion; analytics owns hopping-window popular-item rankings; data access owns in-memory state
and atomic mutation primitives; and the API layer owns routing, JSON translation, and HTTP errors.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: JDK built-in `com.sun.net.httpserver`; no third-party runtime dependencies

**Storage**: In-memory catalog, transactions, inventory, and scan history

**Testing**: JDK compilation; contract checks; manual HTTP smoke checks; supplied load-client runs

**Target Platform**: Local JDK 21+ runtime on a development workstation

**Project Type**: Synchronous HTTP web service

**Performance Goals**: Complete 10 stations for 60 seconds with zero start, scan, or completion
errors; record p95 and p99 for normal and stress runs

**Constraints**: Preserve OpenAPI behavior; do not modify the contract, load client, or mock server;
never allow negative inventory; keep layer dependencies unidirectional

**Scale/Scope**: Fixed startup catalog; concurrent checkout stations; one standalone weekly server

## Constitution Check

*GATE: Passed before Phase 0 research. Re-checked after Phase 1 design: passed.*

| Constitution requirement | Design response | Status |
| --- | --- | --- |
| Preserve the OpenAPI contract and synchronous behavior | The contract map retains all seven endpoints and maps each to an API handler. | Pass |
| Use the unmodified measurement harness | Quickstart uses the supplied load client; no harness changes are planned. | Pass |
| Keep API, transactions, analytics, and data access distinct | The source layout and responsibility map define exactly these four layers. | Pass |
| Protect inventory under concurrency | Completion coordinates atomic inventory mutation through data access. | Pass |
| Commit normal and stress evidence | Quickstart requires reports in `layered-server/reports/`. | Pass |

## Project Structure

### Documentation (this feature)

```text
specs/002-layered-architecture/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── http-api.md
└── tasks.md              # Created later by /speckit-tasks
```

### Source Code (repository root)

```text
layered-server/
├── src/selfcheckout/
│   ├── api/           # HTTP routing, request parsing, response/error encoding
│   ├── transactions/  # Checkout workflow and inventory completion coordination
│   ├── analytics/     # Scan-window and popularity calculations
│   ├── dataaccess/    # Catalog, transaction, inventory, and scan persistence operations
│   └── domain/        # Shared immutable domain values and result types
├── test/selfcheckout/
│   ├── contract/
│   ├── integration/
│   └── unit/
├── reports/
├── build.sh
└── run.sh
```

**Structure Decision**: A single Java service is sufficient: the required boundaries are
responsibility and dependency boundaries, not separately deployed processes. API depends on
transactions and analytics; those layers depend on data access and domain values. Data access and
domain values never depend on API, transactions, or analytics.

## Complexity Tracking

No constitution violations require justification.
