# Implementation Plan: Windowed Analytics Pipeline

**Branch**: `003-windowed-analytics-pipeline` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-windowed-analytics-pipeline/spec.md`

## Summary

Replace the synchronous windowed-analytics critical section with a three-stage asynchronous
pipeline: `ingest` → `window` → `rank-and-publish`. The transaction service continues to
accept a successful scan synchronously, but analytics work is queued and performed by dedicated
workers. The public popularity response and every other OpenAPI response remain unchanged.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: JDK built-in HTTP server and concurrency utilities; no third-party
runtime dependencies

**Storage**: In-memory catalog, transactions, inventory, bounded scan window, immutable published
analytics snapshot, and in-memory pipeline queues

**Testing**: JDK compilation; deterministic analytics unit tests; concurrent integration tests;
existing contract and inventory checks; supplied load-client normal and stress runs

**Target Platform**: Local JDK 21+ runtime on a development workstation

**Project Type**: Synchronous HTTP web service with internal asynchronous processing

**Performance Goals**: Preserve successful synchronous checkout behavior during analytics updates;
complete and record the default 10-station/60-second workload and the required
100-station/120-second workload

**Constraints**: Preserve the OpenAPI contract and unmodified harness; retain a last-1,000-scan
window and refresh on the first scan and each 500th scan; process accepted scans in order exactly
once; publish only complete snapshots; drain accepted analytics work on orderly shutdown; maintain
the API, transaction, analytics, and data-access boundaries

**Scale/Scope**: One standalone layered Java service, a single ordered scan stream, three internal
analytics filters, seven unchanged public endpoints, and two new required workload artifacts

## Constitution Check

*GATE: Passed before Phase 0 research. Re-checked after Phase 1 design: passed.*

| Constitution requirement | Design response | Status |
| --- | --- | --- |
| Preserve the OpenAPI contract and synchronous behavior | No endpoint or response schema changes; scan requests enqueue analytics work after successful scan acceptance. | Pass |
| Use the unmodified measurement harness | Quickstart invokes the supplied client without code changes and stores its generated JSON output. | Pass |
| Keep API, transactions, analytics, and data access distinct | Only the analytics package gains filter and message types; routes retain the existing analytics query boundary. | Pass |
| Compose windowed analytics as named filters connected by pipes | `ingest` → `window` → `rank-and-publish` is documented with one responsibility per filter and FIFO queues between stages. | Pass |
| Preserve accepted scans through orderly shutdown | A close protocol stops intake, sends terminal messages after queued work, and joins workers before process exit. | Pass |
| Commit required evidence and pipeline description | Quickstart requires a default report plus a 100-station/120-second report in `layered-server/reports/`; architecture documentation records the topology. | Pass |
| Protect inventory and partial completion | The transaction and data-access paths remain unchanged; analytics observes only successful scans. | Pass |

## Project Structure

### Documentation (this feature)

```text
specs/003-windowed-analytics-pipeline/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── pipeline-and-http.md
└── tasks.md             # Created later by $speckit-tasks
```

### Source Code (repository root)

```text
layered-server/
├── src/selfcheckout/
│   ├── api/           # HTTP routing and response encoding; unchanged public boundary
│   ├── transactions/  # Successful scan acceptance and checkout workflow
│   ├── analytics/     # Pipeline coordinator, filters, messages, and published snapshots
│   ├── dataaccess/    # Catalog, transaction, and inventory state
│   └── domain/        # Shared immutable values
├── test/selfcheckout/
│   ├── integration/   # Concurrent and drain checks
│   └── unit/          # Deterministic window and ranking checks
├── reports/           # Generated load-client JSON artifacts
├── ARCHITECTURE.md    # Filter topology and connection documentation
├── build.sh
└── run.sh
```

**Structure Decision**: Extend the existing single Java service. The feature belongs entirely
inside its analytics boundary, except for the existing transaction-to-analytics call becoming a
fast enqueue operation and the application adding lifecycle management. No new deployable
service, public route, or persistence layer is necessary.

## Complexity Tracking

No constitution violations require justification.
