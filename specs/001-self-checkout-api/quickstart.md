# Quickstart: Validating the Monolith Week

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contracts**:
[contracts/README.md](./contracts/README.md) | **Date**: 2026-09-17

How to build, run, and prove this week's implementation. The five sections map one-to-one onto
the constitution's five quality gates, in the order the constitution requires. A week is
submittable when all five pass.

> Nothing here exists yet — this is the validation plan the implementation must satisfy. Code
> arrives with `/speckit-tasks` and `/speckit-implement`.

## Prerequisites

- **JDK 21+** with `javac` on the path — a full JDK, not a JRE. Required by the harness as well
  as by this implementation.
- A POSIX shell for the `build.sh` / `run.sh` scripts (Git Bash is fine on Windows).
- Nothing else. No database, no broker, no container runtime (R3).

Verify:

```bash
java -version && javac -version
```

## Build and run

Build the implementation and the harness once each:

```bash
cd monolith && ./build.sh
```

```bash
cd load-client && ./build.sh
```

Start the service — it listens on port 8080, the harness's default `--baseUrl`, so no flags are
needed on either side:

```bash
cd monolith && ./run.sh
```

Configuration lives in `src/main/resources/application.yaml` and can be overridden on the
command line: `catalogSize` (default 2000), `stockPerItem` (10000), `lowStockThreshold` (50),
`windowSize` (1000), `slideInterval` (500). A configuration violating FR-027 — `windowSize < 1`
or `slideInterval` outside `1..windowSize` — fails at startup rather than being silently
corrected (R9). Keep defaults for submitted runs: the catalog must be identical across weeks
for the popularity cross-check to mean anything (R11).

Smoke-check that it is up:

```bash
curl -s http://localhost:8080/items | head -c 200
```

---

## Gate 1 — Contract conformance

Every operation must return its documented outcome for success *and* for each documented
failure, before any performance number is recorded.

```bash
cd monolith && ./mvnw test -Dtest='contract.*'
```

**Expected**: green, with one test per row of the
[conformance matrix](./contracts/README.md#conformance-matrix). Watch these in particular —
they are the rows most easily got wrong:

- all three 409 reasons distinguishable by `error` code;
- a SKU whose stock **equals** the low-stock threshold is *not* alerted (strictly below);
- `?threshold=0` returns an empty alert list;
- a popular-items query between boundaries returns the last completed boundary's snapshot;
- a query before the first boundary returns an empty ranking with both bounds zero;
- `?limit=0` is refused.

Optional cross-check: run the same harness against `mockserver/` and compare response shapes.
The mock server is a behavioral reference only, never a design reference.

---

## Gate 2 — Normal run

```bash
cd load-client && ./run.sh --baseUrl=http://localhost:8080
```

**Expected**: zero errors on `START_TRANSACTION`, `SCAN_ITEM`, and `COMPLETE_TRANSACTION`. In
particular **zero `INSUFFICIENT_STOCK`** — default stock (2000 × 10000) far exceeds what a
default-scale run scans, so any conflict here means a claim-accounting bug, not a genuine
shortage (SC-002).

Restart the service before each measured run so stock starts from a known baseline.

---

## Gate 3 — Stress run

Same client, higher concurrency and longer duration:

```bash
cd load-client && ./run.sh --baseUrl=http://localhost:8080 --stations=200 --duration=180
```

**Expected**: the run completes and writes a report. A non-zero error rate is acceptable *only*
if reported and explained in the submission notes. Read p95 and p99, not the mean — tail latency
is where lock contention from the completion apply step (R5) would surface.

---

## Gate 4 — Inventory invariant

Two independent checks. Both are required: the fast tests prove the mechanism, the stress
reconciliation proves it survived real concurrency (Principle IV insists the invariant be
verified under load, not inferred).

**Targeted oversubscription** (SC-005) — a deliberately scarce SKU with many transactions
racing:

```bash
cd monolith && ./mvnw test -Dtest='concurrency.*'
```

**Expected**: exactly one transaction completes per available unit; every other transaction
holding that SKU is refused `INSUFFICIENT_STOCK` and left `CANCELLED` with its claims released;
final stock is exactly zero, never negative; no refused transaction leaves a receipt or a stock
change behind.

**Reconciliation after the stress run** (SC-004, FR-018) — for every SKU:

```text
initialStock - finalStock  ==  total quantity across COMPLETED transactions
finalStock                 >=  0
```

Cancelled and still-open transactions must contribute to neither side. Run this against the
stress run's final state, before restarting the service.

Also worth asserting once, since it is cheap and catches claim leaks (data-model.md
cross-entity invariant 5): for every SKU, `reserved` equals the sum of claimed units across
still-open transactions.

---

## Gate 5 — Reports committed

```bash
cp load-client/reports/report-*.json monolith/reports/
```

Commit both the normal-run and stress-run JSON reports under `monolith/reports/`, together with
notes naming the architecture style and giving the latency and throughput deltas against the
previous week (Principle V).

For this first week there is no previous week — say so explicitly rather than leaving the
comparison blank, and record two things that make later comparisons fair:

- **Framework overhead is in the numbers.** Tomcat and Spring MVC dispatch are part of every
  latency figure (R2). Later weeks are only comparable if they keep the same framework, or the
  difference is called out.
- **Platform threads, not virtual threads.** `spring.threads.virtual.enabled` stays off (R12).
  Note it, so a future week does not silently change it and read the result as architecture.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `INSUFFICIENT_STOCK` during Gate 2 | Claim-accounting bug, not a shortage — stock is provisioned far above what a normal run consumes. Check the grant rule `stock - reserved > 0` and that release decrements by `claimedQty`, not `scannedQty` (R4) |
| Stock goes negative | The completion check is consulting shared stock instead of the transaction's own claims, or claims are being granted without respecting `stock >= reserved` (R4, R5) |
| Reconciliation off by a few units | Claims released twice, or released on refusal *and* again on cancellation — release exactly once (data-model invariant 5) |
| Claimed units never return | A cancellation path that does not release claims. Both terminal states must release: completion and cancellation (FR-017, R6) |
| Deadlock or a stalled run under stress | Basket SKUs locked in inconsistent order during the apply step; sort by SKU before acquiring (R5) |
| Receipt total off by a cent | `double` arithmetic on the money path; totals must accumulate in `long` cents (R7) |
| A SKU at exactly the threshold shows up in low stock | Comparison is `<=` instead of strictly `<` (FR-019) |
| `?threshold=0` returns alerts | Same cause — with strict comparison and non-negative stock, nothing can match (R10) |
| Popularity ranking differs from a prior week | Catalog seeding is not deterministic (R11) — the same configuration must produce an identical catalog |
| Popular items empty well into a run | Expected only before the first boundary at `windowSize`; after that, check the boundary predicate fires at `windowSize`, then every `slideInterval` — not at every multiple of `slideInterval` (R8) |
