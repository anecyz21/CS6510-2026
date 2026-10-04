<!--
Sync Impact Report
- Version change: 1.2.0 → 1.3.0
- Modified principles: III. Layered Architecture & Isolation →
  III. Layered Architecture, Isolation & Pipeline Composition;
  V. Evidence-Based Evaluation → V. Evidence-Based Evaluation & Submission Artifacts;
  Development Workflow & Quality Gates (added pipeline-composition gate)
- Added sections: None
- Removed sections: None
- Follow-up TODOs: None
-->

# Self-Checkout Semester Project Constitution

## Core Principles

### I. API Contract is Authoritative

The provided OpenAPI contract defines the externally visible behavior of the system.
Weekly implementations MUST preserve the documented endpoints, request/response formats,
status codes, and synchronous client-facing behavior. Internal architecture may change
without changing the external API.

Rationale: Maintaining a stable API allows the different architectures to be compared
using the same client and workload.

### II. Test Harness Consistency

The load client and mock server in this repository are measuring instruments, not part of
any week's deliverable. Implementations MUST be measured with the harness exactly as
provided; only its documented CLI flags may vary from run to run.

Rationale: An instrument that never changes is what makes week-to-week numbers
comparable.

### III. Layered Architecture, Isolation & Pipeline Composition

This week's server implementation MUST use a layered architecture with distinct API,
transactions, analytics, and database-access layers. The API layer owns HTTP and OpenAPI
translation; the transactions layer owns checkout workflows and inventory updates; the
analytics layer owns reporting and ranking calculations; and the database-access layer owns
persistence queries and storage concerns. Dependencies MUST flow through these layer
boundaries: API code MUST NOT access persistence directly, and analytics code MUST NOT
duplicate transaction or database-access responsibilities. Implementations MUST remain
independently buildable and runnable and MUST NOT depend on application code from other weeks
unless the assigned architecture explicitly requires it.

Rationale: Explicit boundaries make the server's responsibilities testable, allow internal
changes without altering the API contract, and preserve each weekly architecture as an
independent unit of comparison.

The windowed-analytics functionality MUST be implemented as a small, composable
pipeline of filters connected by internal pipes. Each filter MUST have one named,
documented purpose and MUST communicate only through the adjacent pipe; the HTTP-facing
analytics layer MUST NOT embed the pipeline's batching, windowing, or ranking work.
Pipes MAY use threads with blocking queues, embedded asynchronous messaging, or an
equivalent language-native mechanism, provided synchronous API responses remain
contract-conformant and lifecycle shutdown does not lose accepted scans.

Rationale: Separating ingest, window management, aggregation, and publication exposes
the naturally stream-oriented analytics work without weakening the stable synchronous API.

### IV. Inventory Correctness & Partial Completion

Stock is decremented when a transaction completes, not when an item is scanned. For every
SKU, the drop in stock across a run MUST equal the number of completed line items for that
SKU, and stock MUST never go negative. Any check-then-decrement MUST be atomic with
respect to concurrent completions, and the invariant MUST be verified under stress load
rather than inferred from functional tests. When one or more scanned items are unavailable
at completion, the transaction MUST complete every independently available line item,
MUST NOT decrement stock for unavailable items, and MUST retain each unavailable item in
the basket for a later attempt. The completion response or customer-facing flow MUST
identify each unavailable item; an unavailable line item MUST NOT cause otherwise
available items to be rejected.

Rationale: An implementation can pass every functional test and still sell the last unit
of an item twice once stations run concurrently, which is the failure mode this project
is built to expose. Completing the available portion also prevents a single outage or
stock shortage from unnecessarily blocking the customer's remaining purchase.

### V. Evidence-Based Evaluation & Submission Artifacts

Every submission includes the load client's timestamped JSON reports for a normal run and
a stress run, committed alongside that week's code and reachable from the submitted
repository URL. For the pipeline week, those reports MUST result from the default client
parameters and from `--stations=100 --duration=120`, respectively. Analysis MUST report
p95 and p99 latency in addition to mean latency and SHOULD compare results against
previous weeks using comparable hardware and workload configurations. The popular-items
ranking is expected to hold steady across weeks; a ranking that moves materially MUST be
explained. Submission notes MUST name every analytics filter, state its single purpose,
and identify the pipe mechanism and filter order.

Rationale: Tail latency is where lock contention, network hops, and orchestration overhead
actually surface, and a mean hides exactly those effects.

## Technology & Contract Constraints

- The mock server shows what the contract means by example. It is not an architectural
  model and MUST NOT be cited as a design precedent.

## Development Workflow & Quality Gates

A week's implementation is submittable once all six gates pass, in order:

1. **Contract conformance** — every endpoint responds with the documented shape and status
   codes. Behavior MAY be cross-checked against the mock server.
2. **Normal run** — completes with no errors on
   `START_TRANSACTION`, `SCAN_ITEM`, or `COMPLETE_TRANSACTION`.
3. **Stress run** — a higher-concurrency run. A non-zero error rate is acceptable here only if it is
   reported and explained.
4. **Inventory invariant** — Principle IV is checked against the stress run's final state.
5. **Reports committed** — the JSON reports from gates 2 and 3 are committed under that
   week's `reports/` directory, with notes naming the architecture style and the latency
   and throughput deltas against the previous week.
6. **Pipeline composition** — the windowed-analytics filters and their ordered pipes are
documented, and a shutdown test or equivalent evidence shows that accepted scans are not
lost while the pipeline drains.

Changes to the contract, the load client, or the mock server fall outside this workflow
and require an amendment first.

## Governance

This constitution supersedes conflicting ad-hoc practice in this repository. Where a
weekly assignment brief and this document disagree, the brief governs what to build and
this document governs how it is built, measured, and reported.

**Amendments** are made by editing this file, and MUST state a rationale, bump the
version, and update the `Last Amended` date. An amendment that touches the contract, the
harness, or the inventory invariant MUST also record which earlier measurements it
invalidates, because changing any of the three breaks comparability with every week
already submitted.

**Versioning** of this document follows semantic versioning: MAJOR when a principle is
removed, redefined incompatibly, or a quality gate is dropped; MINOR when a principle or
section is added or guidance is materially expanded; PATCH for clarifications and wording
that leave obligations intact.

**Compliance** is reviewed at each weekly submission: the six gates above MUST pass and
the reports MUST be present before a week counts as complete. Complexity in an
implementation SHOULD be justified by the week's assigned architecture; complexity that
serves neither the architecture nor the contract SHOULD be removed.

**Version**: 1.3.0 | **Ratified**: 2026-09-17 | **Last Amended**: 2026-10-03
