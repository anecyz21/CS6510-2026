# Specification Quality Checklist: Self-Checkout System API

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-17
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Coverage against the contract

Every operation in `spec/self-checkout-openapi.yaml` v1.1.0 has requirements and at least
one acceptance scenario.

| Operation | Requirements | Scenarios |
|---|---|---|
| `GET /items` | FR-001, FR-002 | US1 §1 |
| `POST /transactions` | FR-003, FR-004 | US1 §2 |
| `POST /transactions/{id}/items` | FR-005–FR-009 | US1 §3, §6 |
| `POST /transactions/{id}/complete` | FR-010–FR-013 | US1 §4, §5; US2 §2–§5 |
| `GET /transactions/{id}` | FR-014 | US5 §1, §2 |
| `GET /inventory/low-stock` | FR-019–FR-021 | US3 §1–§4 |
| `GET /analytics/popular-items` | FR-022–FR-027 | US4 §1–§5 |
| Inventory correctness (cross-operation) | FR-015–FR-018 | US2 §1, §6 |
| Boundary rules (cross-operation) | FR-028–FR-032 | verified by SC-007 |

All three documented conflict reasons for completion are covered: not-open and empty basket
by FR-012, insufficient stock by FR-013.

## Notes

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`

### Judged-pass items, with reasoning

**"No implementation details"** — the spec necessarily describes an externally visible HTTP
contract, because that contract *is* the feature under Constitution Principle I. Requirements
are phrased as observable behavior and outcomes rather than as paths, verbs, or status codes,
and the Contract Surface table is a traceability index to the normative file rather than a
restatement of it. No language, framework, storage engine, or architecture is named anywhere
in the spec.

**"Technology-agnostic success criteria"** — SC-009 names latency percentiles and throughput.
These are workload-level observations produced by the fixed measuring instrument the project
already mandates (Constitution Principles II and V), not properties of any technology.

**FR-017 altitude** — the requirement fixes the *observable* rules (a scan is always
recorded; an available unit is associated with the transaction; a scan without a claim is
still recorded; completion succeeds only when every scanned unit holds a fulfillable claim;
claims release on completion or cancellation; no unit is claimed twice) while leaving
representation and coordination unconstrained. The scheme chosen for the current week lives
in [plan.md](../plan.md) and [research.md](../research.md), which keeps this spec reusable by
every later architecture.

### Deliberate omissions

- **No absolute latency target.** The project compares each week against earlier weeks on
  comparable hardware rather than against a fixed threshold, so a number here would invent a
  requirement the course does not have. Recorded in Assumptions.
- **No fixed station counts or durations.** Those are load-client flags — properties of the
  measuring instrument, not of the contract.
- **No retry semantics.** The contract defines none for a refused completion, so the spec
  states only that the transaction is not completed and leaves what follows outside scope.
- **No stock-effect-idempotence requirement.** Dropped as redundant: FR-012 already refuses
  completion of a transaction that is not open, which is the only way a second completion
  attempt could arise, and FR-010's all-or-nothing rule plus FR-018's reconciliation
  invariant already forbid a double decrement.

### Boundary decision: low-stock comparison

The contract's domain-model narrative says low-stock alerts are generated when stock "drops
below a configurable threshold", so the spec specifies **strictly below** throughout (FR-019,
SC-010, US3 §1, §3, §4). A SKU whose stock equals the threshold is not reported. US3 §4 tests
that boundary directly, and no divergence from the contract remains.

One consequence worth knowing: because stock is never negative (FR-015), a query with
`threshold=0` can never match anything and always returns an empty alert list. Recorded in
the spec's edge-case table so it is not mistaken for a bug.
