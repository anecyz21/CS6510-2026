# Implementation Plan: Self-Checkout System API — Monolith Week

**Branch**: `001-self-checkout-api` | **Date**: 2026-09-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-self-checkout-api/spec.md`

## Summary

Implement the full self-checkout contract as a single-process Spring Boot monolith, with MySQL
as the durable system of record for catalog, inventory, transactions, and analytics. The design problem this week is
not structure but concurrency: scans never gate on stock, so a basket can outrun the shelf,
and completion has to be all-or-nothing across every SKU in that basket.

The approach is an explicit two-number inventory state per SKU — physical `stock` and
`reserved` — maintained under the invariant `stock >= reserved >= 0`. A scan claims one unit
when an unclaimed unit is available and records the unit unclaimed when none is. Because every
claim is backed by a physical unit, completion reduces to a **transaction-local** check —
does every scanned unit hold a claim? — after which each per-SKU decrement is guaranteed safe.
Locks over the basket's SKUs, taken in sorted order, exist only to land the multi-SKU decrement
as one step so no reader sees a half-applied basket.

Two consequences shape the week. The contended race is decided at **scan** time, not at
completion, so completion contention is low. And an `INSUFFICIENT_STOCK` refusal is
**terminal**: claims are only taken at scan time and nothing removes a unit from a basket, so a
refused transaction can never complete — it is cancelled and its claims returned to the pool.

Full decision records with alternatives: [research.md](./research.md).

## Technical Context

**Language/Version**: Java 21 (project floor per README; required for the harness)

**Primary Dependencies**: Spring Boot 3.x — `spring-boot-starter-web` (embedded Tomcat,
synchronous MVC), `spring-boot-starter-validation`, `spring-boot-starter-jdbc`,
`spring-boot-starter-test`, MySQL Connector/J, and Flyway for versioned schema migrations.
No messaging or ORM. Exact versions are pinned in `pom.xml` at implementation time.

**Storage**: MySQL 8 / InnoDB. Catalog items, inventory, transactions, transaction lines and
claims, scan events, popularity snapshots, and inventory movements are persisted in normalized
tables. Flyway creates the schema and deterministic catalog seed data. MySQL remains the
authoritative state and is never reset by a service restart.

**Testing**: JUnit 5 with `@SpringBootTest` + MockMvc for contract conformance, plus two
first-class correctness suites — a concurrency invariant test and a per-SKU reconciliation
test (R14).

**Target Platform**: JVM 21, single process, HTTP on port 8080 (Spring Boot's default, which
is also the harness's default `--baseUrl`). Runs on Linux, macOS, and Windows unchanged.

**Project Type**: Web service, single project.

**Performance Goals**: No absolute target — the contract sets none. This week's numbers *are*
the baseline that later architectures are compared against, so the goal is a clean,
reproducible measurement: mean, p50, p95, p99, and max per operation, plus transaction and
item throughput, from the unmodified harness.

**Constraints**: Contract conformance is a gate, not a goal (Principle I). Every operation
synchronous at the boundary (FR-028). Claims invisible through the API (FR-031). Stock never
negative and per-SKU reconciliation exact (FR-015, FR-018). Money exact to the cent (FR-030).
Low-stock comparison strictly below threshold (FR-019). Window and limit parameters validated
against FR-027. The harness is not modified (Principle II). Default framework settings,
including platform threads rather than virtual threads, so week-to-week deltas stay
attributable (R12).

**Scale/Scope**: 7 operations. Default seeded catalog of 2000 SKUs at 10000 units each with a
low-stock threshold of 50; hopping window of 1000 scans sliding every 500. Concurrency comes
from the harness — roughly 10 stations at default scale, a couple of hundred under stress.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Checked against constitution v1.0.1.

| Principle | Verdict | How this plan satisfies it |
|---|---|---|
| **I. API Contract is Authoritative** | PASS | Every operation, payload, and status code comes from `spec/self-checkout-openapi.yaml`, including the newly documented `INSUFFICIENT_STOCK` reason for the existing 409. Gate 1 contract tests cover all three conflict reasons. No endpoint, status code, or schema is added. |
| **II. Test Harness Consistency** | PASS | `load-client/` and `mockserver/` are untouched. Measurement comes from the harness as provided, varying only its documented flags. The implementation lives in its own directory and serves 8080 so no flag change is needed to reach it. |
| **III. Architecture Isolation** | PASS | A new top-level `monolith/` directory with its own `pom.xml`, Maven wrapper, `build.sh`/`run.sh`, migrations, and database configuration. No shared parent POM, no code borrowed from any other week, nothing added to the repo root that later weeks must inherit. |
| **IV. Inventory Correctness** | PASS | Stock moves only at completion (FR-015). Claims are granted atomically under `stock >= reserved` and the basket's decrement is applied as one step under sorted-order locks (FR-016, FR-017, R5). The invariant is verified *under load* by a dedicated concurrency test and re-checked against the stress run's final state at Gate 4 — not inferred from functional tests. |
| **V. Evidence-Based Evaluation** | PASS | Normal and stress run reports committed to `monolith/reports/`, with notes naming the architecture and reporting mean alongside p95/p99. Deterministic catalog seeding (R11) is what makes the popularity cross-check meaningful across weeks. |
| **Mock server is not a design precedent** | PASS | The mock server informs *behavior* only — what the contract means by example. The design here departs from it on the substance: an explicit claim model with a documented insufficient-stock outcome, where the mock server has no claim concept at all. Using concurrent maps for storage is not inheritance of its architecture; its architectural deficiency is the absent atomicity, not the data structure. |

**Recorded procedural deviation.** The constitution's workflow section says changes to the
contract "require an amendment first". The contract was amended this session on direct user
instruction, documenting `INSUFFICIENT_STOCK` under the existing 409 — description and
examples only, no endpoint, status code, or schema change. Under Governance, the assignment
brief governs *what* to build, so the instruction stands. Nothing is invalidated: no
measurements exist yet, no weekly reports are committed, so there is no comparability to
break. Noting it here rather than leaving the record silent.

**Post-Phase 1 re-check**: PASS, updated for the MySQL design. The Phase 1 design introduces
JDBC, MySQL, Flyway, and durable persistence but no new API surface. `contracts/` documents the
existing contract rather than extending it, and the claim entity in `data-model.md` is marked
internal in line with
FR-031. The one behavior added during re-sync — cancelling a transaction refused for
insufficient stock (R6) — uses the `CANCELLED` status the contract already defines and adds no
operation.

## Project Structure

### Documentation (this feature)

```text
specs/001-self-checkout-api/
├── plan.md              # This file
├── spec.md              # Feature specification (33 FRs, 10 SCs)
├── research.md          # Phase 0 output — 13 decision records
├── data-model.md        # Phase 1 output — entities, invariants, state transitions
├── quickstart.md        # Phase 1 output — build, run, and the five gates
├── contracts/           # Phase 1 output
│   ├── README.md        # Normative pointer + operation/outcome conformance matrix
│   └── error-codes.md   # Error code catalog
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
monolith/                                  # this week's implementation, self-contained
├── build.sh                               # matches load-client/ and mockserver/ convention
├── run.sh
├── mvnw, mvnw.cmd, .mvn/                  # Maven wrapper — no global Maven needed
├── pom.xml
├── reports/                               # Principle V: committed run reports live here
├── src/main/java/edu/northeastern/cs6510/selfcheckout/
│   ├── SelfCheckoutApplication.java       # entry point
│   ├── api/                               # controllers, DTOs, @ControllerAdvice error mapping
│   ├── catalog/                           # catalog seeding and lookup
│   ├── inventory/                         # stock + claims + low-stock (the hard part)
│   ├── transaction/                       # lifecycle, basket, receipt assembly, cancellation
│   ├── analytics/                         # scan sequence, ring buffer, window snapshots
│   └── config/                            # seeding and window configuration properties
├── src/main/resources/
│   ├── application.yaml                   # port, MySQL connection settings, catalog/window defaults
│   └── db/migration/                       # versioned Flyway schema and seed migrations
└── src/test/java/edu/northeastern/cs6510/selfcheckout/
    ├── contract/                          # one class per operation, all documented outcomes
    ├── concurrency/                       # oversubscription + reconciliation under load
    └── unit/                              # claim logic, window math, money, receipts
```

**Structure Decision**: A single Maven project under `monolith/`, chosen so this week is
independently buildable and re-measurable later (Principle III) and so the repo root stays
free for the weeks that follow.

Packages group code by domain — `catalog`, `inventory`, `transaction`, `analytics` — for
navigability, **not** as enforced layers. There is no controller/service/repository stack or
interface-per-class indirection. Thin JDBC stores keep SQL and row mapping local to each domain
while domain code calls domain code directly in one address space. That restraint is deliberate.
Introducing layering now would spend the layered week's distinguishing feature early and blur
the comparison the semester is built on. The one boundary that *is* enforced is `api/` —
controllers and DTOs are the only code that knows about HTTP, which keeps FR-030's
synchronous boundary and FR-031's uniform error payloads in a single place.

`inventory/` carries the week's real weight: it owns the two-number per-SKU state, the claim
grant rule, claim release, and the ordered-locking apply step. It is deliberately the only
package permitted to mutate stock.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations. All six constitution checks pass, so this table is intentionally empty.

Spring Boot is a framework dependency rather than added complexity in the constitutional
sense — the constitution's Technology Constraints permit weekly implementations to bring
frameworks, and its use here follows the week's assigned architecture (R2). The one structural
choice worth naming is the ordered per-SKU locking in R5, which is more intricate than a single
global completion lock; it is justified because a global lock would serialize every completion
and make week 1 a measurement of lock contention rather than of the monolith. Note that FR-017
made this *less* intricate than an earlier draft: since feasibility is transaction-local, the
locks only serialize the apply step and never arbitrate between competing completions.
