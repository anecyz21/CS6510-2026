# Self-Checkout Architectural Characteristics Analysis

## Required characteristics

| Characteristic | Concrete requirements |
| --- | --- |
| **Backstock concurrent consistency** | The system must prevent overselling when many stations complete at once. For every SKU, stock can decrease only after a successful completion, can never be negative, and `initial stock - final stock` must equal completed receipt quantities. With inventory provisioned for the normal workload, starts, scans, and completions must have zero conflicts; a genuine shortage may return a distinct `409 INSUFFICIENT_STOCK` without changing stock. |
| **Reliability and durability** | Transactions, scanned lines, claims, stock movements, and analytics events must survive a service restart. MySQL is the source of truth, and a completed sale must not be lost or charged twice. |
| **Correctness of checkout behavior** | Every scan represents exactly one item, is accepted even when stock is zero, and updates the basket total in exact cents. Completion sells every currently available unit; unavailable units are not charged and stay in the open basket for a later completion. A completion with no available units returns `409 INSUFFICIENT_STOCK` and changes no stock, receipt, or basket. Each successful completion returns a receipt for only the units it purchased, under the same transaction ID. Receipts aggregate repeated SKUs correctly. |
| **Performance and scalability** | The service must support concurrent checkout stations using synchronous HTTP. The normal workload must complete without errors, while stress runs must finish and record mean, p95, and p99 latency for start, scan, and complete operations. |
| **Availability and recoverability** | The application must start predictably on port 8080, run from a self-contained Maven project, and provide clear database configuration. A failed or cancelled transaction must release its inventory claims so another customer can purchase the stock. |
| **API contract compatibility** | All seven specified endpoints must preserve their documented requests, response shapes, status codes, and stable machine-readable error codes. Internal claims and reconciliation data must not be exposed through the API. |
| **Observability and auditability** | The system must retain stock movements and enough transaction data to reconcile inventory after a load run. It must provide low-stock alerts and reproducible popular-item rankings, while storing normal and stress JSON reports for later comparison. |
| **Security and configuration hygiene** | Database credentials must be supplied through environment configuration rather than committed to source control. The service must use least-privilege database access in a production deployment. |

## Three implementation priorities

### 1. Backstock concurrent consistency

This is the first priority because an incorrect stock count directly causes overselling. Completion is a saga of local transactions, each in one service's private database; no transaction spans databases:

1. Checkout freezes the basket under a stable completion-attempt ID.
2. Inventory reserves `min(requested, available)` for each SKU, using row locks and conditional updates that enforce `stock ≥ reserved ≥ 0`.
3. Checkout durably records the receipt intent.
4. Inventory commits the reservation. This is the only step that decrements physical stock.
5. Checkout finalizes the receipt and reduces the basket.

Every step is idempotent on the attempt ID. A timeout is treated as an unknown outcome and recovered by repeating or querying the same step, not by compensating. Once the receipt intent is durable, the attempt only moves forward and is never cancelled. See [research R8](specs/004-domain-services/research.md#r8-completion-saga) and the [completion attempt state machine](specs/004-domain-services/data-model.md#completion-attempt-state-machine).

**Trade-off:** database locks and transactional checks increase completion latency and can reduce peak throughput under heavy contention. This is preferable to accepting a fast request that sells the same physical unit twice.

### 2. Reliability and durable recovery

The second priority is making MySQL authoritative for transaction state, scanned items, claims, and stock movement. A service restart must not erase an open basket, a completed receipt, or the evidence needed to reconcile inventory.

**Trade-off:** durable writes on each scan and completion cost more I/O than keeping the basket only in memory. They also make the implementation more complex than a purely in-memory design, but avoid losing scans or leaving untracked reservations after a crash.

### 3. Contract-compatible performance under load

The third priority is keeping the synchronous API responsive with the provided load client, especially at p95 and p99 latency. The implementation will keep the catalog stable, use indexed SQL queries, aggregate receipt lines, and avoid unnecessary remote calls because this is a monolith.

**Trade-off:** optimizing for the common checkout path may defer convenience features such as extensive real-time reporting or arbitrary ad-hoc queries. Analytics is published as bounded snapshots rather than recomputed from all scan history for every request; this improves response time but means a query returns the most recently completed window boundary rather than an always-live count.
