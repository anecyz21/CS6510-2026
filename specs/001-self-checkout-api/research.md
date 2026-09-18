# Phase 0 Research: Self-Checkout System API — Monolith Week

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-09-17

All Technical Context unknowns are resolved below. Three decisions (R1, R2, R3) came from the
user; the rest follow from the spec, the contract, and the constitution. Requirement
references track spec.md's current numbering (FR-001–FR-032).

---

## R1: Architecture style

**Decision**: Single-process monolith. One deployable, one address space, no internal service
boundaries and no network hop inside the system.

**Rationale**: User decision. It is also the right first week: it establishes the baseline
every later architecture is measured against, and it makes the claim mechanism (FR-017)
achievable with in-process synchronization, so week 1 measures the cost of the architecture
rather than the cost of getting concurrency wrong.

**Alternatives considered**: Layered monolith — deferred deliberately, so the layered week has
something to differ by. Service-based and microservices — later weeks; splitting inventory out
now would conflate two variables in the first data point.

---

## R2: Language and framework

**Decision**: Java 21 with Spring Boot 3.x (`spring-boot-starter-web`, embedded Tomcat,
synchronous MVC). Pin the exact patch release in `pom.xml` at implementation time.

**Rationale**: User decision. Spring MVC's blocking request model maps directly onto FR-028
(every operation synchronous at the boundary), and `spring-boot-starter-validation` plus
`@ControllerAdvice` give FR-004, FR-027, and FR-029 (request validation, parameter
constraints, uniform error payloads) without hand-rolled plumbing. Java 21 is already the
project's floor.

**Alternatives considered**: Zero-dependency JDK `com.sun.net.httpserver` — matches the
harness exactly and adds no framework latency, but hand-rolls routing, JSON, and error
mapping. Javalin/Helidon SE — lighter, but Spring's validation and transaction infrastructure
pays off in later weeks.

**Consequence to watch**: framework overhead is part of every latency number. Week 1's p95/p99
includes Tomcat and Spring MVC dispatch; comparisons against later weeks are only fair if
those weeks keep the same framework. Record it in the submission notes (Principle V).

---

## R3: Storage for inventory, claims, and transactions

**Decision**: In-memory only. `ConcurrentHashMap` keyed by SKU for inventory and by
transaction id for transactions; no database, no persistence across restarts.

**Rationale**: The user expressed no preference, so this is our call. The workload is a
fixed-duration load test that seeds its own catalog at startup and never needs to survive a
restart; a database would put connection-pool and commit latency into the very first data
point, against which every later week's delta is measured; and FR-016's atomicity is provable
in-process, whereas pushing it into SQL makes correctness depend on isolation levels that
differ per engine.

**Alternatives considered**: Embedded H2 with JPA and `@Transactional` — row-level locking
would enforce FR-016 at the database rather than in application code, genuinely attractive for
correctness, but it front-loads schema and ORM work and muddies the baseline. External
PostgreSQL — the honest choice once later weeks need state shared across services.

**Revisit when**: inventory becomes its own service. This decision does not carry over.

---

## R4: Per-SKU inventory state and the claim invariant

**Decision**: Each SKU owns two integers: `stock` (physical units on hand) and `reserved`
(units currently claimed by open transactions). The system maintains, at every observable
moment:

```text
stock >= reserved >= 0
```

A scan claims one unit if and only if `stock - reserved > 0`, incrementing `reserved`.
Otherwise the scan still succeeds and records the unit **unclaimed** (FR-007, FR-017).
Claims are released — `reserved` decremented — when the transaction completes or is cancelled.

**Rationale**: This invariant does the heavy lifting. Because `reserved` can never exceed
`stock`, every claim is backed by a physical unit, so a transaction whose every scanned unit
holds a claim is guaranteed to complete. Insufficient stock (FR-013) therefore arises from
exactly one cause: a scan that could not obtain a claim.

**Alternatives considered**: Tracking only `available = stock - reserved` — loses the ability
to report physical stock for FR-021, which must ignore claims. Claiming at completion instead
of at scan — forfeits the early-claim property and contradicts FR-017.

---

## R5: Completion — a transaction-local check, then an atomic apply

**Decision**: Completion feasibility is a property of the transaction alone. Per FR-017, a
transaction completes only if every scanned unit holds a claim:

```text
fulfillable   iff   for every sku:  held[sku] == scanned[sku]
apply(sku)          stock -= scanned[sku];   reserved -= held[sku]
```

The check reads no shared inventory state. The apply step still takes each affected SKU's
lock, acquired in sorted SKU order, so the whole multi-SKU decrement lands as one step.

**Rationale**: Two things fall out of R4's invariant. First, because `held == scanned` and
`stock >= reserved >= held`, every per-SKU decrement is individually guaranteed safe —
`stock - scanned = stock - held >= reserved - held >= 0` — so there is no cross-SKU
feasibility question to resolve. Second, the locks are therefore not there to resolve
contention but to make the decrement atomic, satisfying FR-010's all-or-nothing rule and
keeping concurrent low-stock and reconciliation readers from observing a half-applied basket.
Sorted acquisition order gives a total order every thread agrees on, so no deadlock. Baskets
are small — at most 20 distinct SKUs under the harness — so holds are short.

**The race moves to scan time.** Whichever transaction's scan claims the last unit wins it;
completion merely cashes in claims already held. Once scanning stops, each transaction's
outcome is already determined. That is a real simplification over checking unclaimed units
against free stock at completion, and it means completion contention is far lower than a
design where completions fight over the same free units.

**Alternatives considered**: Re-check unclaimed units against free stock at completion (an
earlier draft of this design) — rejected by FR-017, and it made a transaction's outcome depend
on completion timing rather than on scan order. One global completion lock — trivially correct
and trivially serial; it would cap completion throughput at one CPU and make week 1 a measure
of lock contention. No locking at all — each decrement is safe in isolation, but a reader could
observe a partially applied basket, violating FR-010.

---

## R6: Insufficient-stock refusal is terminal, so it cancels

**Decision**: When completion is refused for insufficient stock, mark the transaction
`CANCELLED` and release the claims it holds. Return the `INSUFFICIENT_STOCK` conflict. No
stock changes.

**Rationale**: Claims are taken only at scan time (FR-017). A unit that failed to obtain a
claim can therefore never acquire one later — so a transaction refused for insufficient stock
can *never* subsequently complete, no matter what happens to inventory. The spec defines no
retry semantics and no way to remove a unit from a basket, which leaves the transaction
permanently unfulfillable. Cancelling it is the only outcome that terminates the transaction
honestly and returns its claims to the pool instead of stranding them for the rest of the run.

This is exactly the case the spec's Assumptions sanction: "A transaction may be cancelled
either by the customer abandoning it or by the system when its scanned units cannot be
fulfilled for lack of stock." FR-013 requires only that the transaction not be marked
*completed*; `CANCELLED` satisfies that, is visible through transaction inspection, and is
excluded from reconciliation by FR-018. Releasing a claim is not a stock movement, so
FR-013's "no stock changes" holds.

**Alternatives considered**: Leave the transaction `OPEN`. The spec permits it, and the
Assumptions do say claims are held while a transaction stays open — but with no retry path the
transaction is stuck forever while holding inventory nothing can use, which would slowly
starve the SKUs under contention. Auto-cancel and *retain* claims — pointless: a cancelled
transaction can never spend them.

**Flagged for review**: the spec permits but does not require auto-cancellation here. If a
future week's workload wants refusals to stay open, this is the decision to revisit.

---

## R7: Monetary representation

**Decision**: Store and accumulate money as `long` minor units (cents). Convert to a decimal
only when serializing, and emit exactly two decimal places.

**Rationale**: FR-030 requires running totals and receipt totals to equal the sum of unit
prices exactly. Accumulating `double` across up to 20 additions per transaction and millions
of transactions per run invites drift that would fail SC-006. The contract types the fields as
JSON numbers, which a two-decimal value satisfies without any schema change.

**Alternatives considered**: `BigDecimal` throughout — exact and self-documenting, but
allocates on every scan on the hot path. `double` — what the mock server does; rejected
because exactness is graded here.

---

## R8: Scan sequence and the hopping window

**Decision**: An `AtomicLong` assigns each accepted scan its sequence number at the moment the
scan is accepted — that increment *is* the linearization point FR-022 names, so sequence
numbers are unique, strictly increasing, and independent of wall-clock timestamps. Scans write
their SKU into a fixed ring buffer of `windowSize` slots at index `(seq - 1) % windowSize`.

Boundaries follow FR-023 exactly: the first at sequence position `windowSize`, then every
`slideInterval` positions after — 1000, 1500, 2000… for the defaults. The scan whose sequence
number lands on a boundary recomputes counts over the buffer and publishes an **immutable
snapshot** into a single `volatile` reference. Queries read that reference and never compute,
so a query between boundaries returns the most recently completed boundary's result.

```text
windowEnd   = boundary position
windowStart = windowEnd - windowSize + 1     (always >= 1, since the first boundary is windowSize)
```

Before the first boundary an empty snapshot with both bounds zero is published at startup
(FR-024).

**Rationale**: Because the first boundary is at `windowSize`, the ring buffer is exactly full
whenever a recomputation happens — every slot holds a scan inside the window, with no
partial-fill special case and no `max(1, …)` clamp. Recomputation is O(`windowSize`) — 1000
entries every 500 scans — and happens on the scan path, off the query path where latency is
measured.

**Alternatives considered**: Boundaries at every multiple of `slideInterval` starting from
`slideInterval` (500, 1000, 1500…) — an earlier reading of the contract, rejected because
FR-023 fixes the first boundary at `windowSize`; it also required handling a half-full buffer.
Recompute on every query — turns a cheap read into O(`windowSize`) work and lets the analytics
call distort p99. Incremental counters per scan — O(1) per scan, but tail eviction gets fiddly
for no measured benefit.

---

## R9: Parameter validation

**Decision**: Validate FR-027's constraints at the boundary — `windowSize >= 1`,
`1 <= slideInterval <= windowSize` on configuration at startup (fail fast, refusing to boot on
a bad combination), and `limit >= 1` on the popular-items query, rejected as a client error.

**Rationale**: A `slideInterval` greater than `windowSize` would skip scans entirely and a
non-positive `windowSize` has no meaning, so configuration is better rejected at startup than
silently corrected. The query parameter is caller-supplied and belongs in the same uniform
error path as every other refusal (FR-029).

**Alternatives considered**: Clamping bad values silently — hides operator error and would
make two runs with different configuration look identical in their reports.

---

## R10: Low-stock crossing timestamps

**Decision**: Each SKU records `lowStockSince`, stamped when a completion drives its physical
stock **strictly below** the configured default threshold, and cleared if stock ever rises to
or above it. A low-stock query reports that timestamp. For a query supplying its own threshold,
report `lowStockSince` when set and otherwise the report's generation time.

**Rationale**: FR-019 compares strictly below, matching the contract's "drops below" wording,
so equality with the threshold is not an alert and must not stamp a crossing. The contract
requires a `triggeredAt` per alert, but a per-query threshold can name a level the SKU crossed
at some unrecorded moment; tracking a timestamp per possible threshold is absurd, and the
fallback is honest and free.

**Note**: because stock is never negative (FR-015), a query with `threshold=0` can never match
anything. The empty result is correct, not a bug.

**Alternatives considered**: Always report generation time — loses real information for the
common default-threshold query. A full stock-history log — real bookkeeping for a field nothing
in the graded workload reads.

---

## R11: Deterministic catalog seeding

**Decision**: Seed the catalog at startup from configuration (`catalogSize`, `stockPerItem`,
`lowStockThreshold`) with SKUs formatted `SKU-%06d` from 1, names derived from the index, and
prices from a fixed-seed generator. The same configuration MUST produce a byte-identical
catalog on every run and in every week.

**Rationale**: SC-008 expects the top-10 popularity ranking to agree across implementations.
That only holds if the catalog is identical, because the load client's Zipf-like sampler
weights items by catalog position. A randomly seeded catalog would make the analytics
cross-check meaningless and produce a false "the architecture changed behavior" signal.

**Alternatives considered**: Random prices per boot — breaks cross-week comparison. A
checked-in catalog fixture — also deterministic, but a file to keep in sync across weeks when
a formula needs nothing.

---

## R12: Server threading model

**Decision**: Embedded Tomcat's default platform-thread pool. Leave
`spring.threads.virtual.enabled` **off** for the baseline, documented as a one-line knob for a
deliberate side experiment.

**Rationale**: Java 21 makes virtual threads available and they would likely help at high
station counts, but enabling them changes the latency profile substantially. Comparing a
virtual-thread week 1 against a platform-thread week 5 would attribute a thread-scheduling
difference to architecture — the confound Principle II exists to prevent.

**Alternatives considered**: Virtual threads on by default — better stress numbers, worse
comparability. Tuning pool size per run — an undocumented variable per run.

---

## R13: Build, run, and configuration surface

**Decision**: Maven with the wrapper (`./mvnw`), plus thin `build.sh` and `run.sh` scripts at
the implementation root to match the convention already used by `load-client/` and
`mockserver/`. Serve on port 8080 — Spring Boot's own default, and the harness's default
`--baseUrl`. Expose `catalogSize`, `stockPerItem`, `lowStockThreshold`, `windowSize`, and
`slideInterval` through `application.yaml` with command-line overrides.

**Rationale**: Principle III wants each week independently buildable and runnable; a
self-contained Maven project with its own wrapper achieves that with no shared parent POM.
Mirroring the existing script shape means anyone who can run the mock server can run this.

**Alternatives considered**: Gradle — equally capable, no advantage here. A shared multi-module
parent across weeks — directly against Principle III.

---

## R14: Test strategy

**Decision**: Four layers, mapped to the constitution's five gates.

| Layer | Covers | Gate |
|---|---|---|
| Contract tests (`@SpringBootTest` + MockMvc) | every operation's success and each documented failure, including all three 409 reasons; threshold equality and `threshold=0`; between-boundary window queries; parameter constraint rejections | Gate 1 |
| Concurrency invariant test | many threads scanning and completing against a deliberately scarce SKU: exactly one transaction completes per available unit, the rest refused `INSUFFICIENT_STOCK` and cancelled, final stock 0, never negative | SC-005, Gate 4 |
| Reconciliation test | bulk transactions across many SKUs, then assert `initial − final == completed quantity` per SKU, with cancelled and refused transactions contributing nothing | FR-018, Gate 4 |
| Unit tests | claim grant/deny rule, the `held == scanned` completion check, claim release on completion and cancellation, window boundary arithmetic and ranking, receipt aggregation, cent-exact totals | supports Gate 1 |

**Rationale**: Principle IV insists the invariant be verified *under load*, not inferred from
functional tests — so the concurrency and reconciliation tests are first-class deliverables.
They run in seconds, unlike the harness's timed runs, keeping the correctness signal fast while
the harness supplies the performance signal.

**Alternatives considered**: Relying on the load client alone for correctness — it reports
latency and errors, not the per-SKU invariant, so an oversell would pass unnoticed. Adding a
reconciliation endpoint for the harness to call — extra API surface, violating FR-031.
