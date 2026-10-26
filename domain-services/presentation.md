---
title: Self-Checkout as Domain Microservices
---

# Self-Checkout as Domain Microservices

- Feature 004: independently deployable services with private stores
- CS6510, branch `microservice-new`

---

# Problem and Goals

- Same public API contract and the same unmodified load client as earlier architectures
- Split the system into independently deployable services, each owning its own data
- Keep purchases correct across services: no overselling, no double charges
- Measure the redesign against the pre-migration baseline

---

# Starting Point: The Service-Based Baseline

- One shared MySQL instance, cross-schema reads and grants
- Completion as a single stored-procedure call (`fulfill_checkout`)
- Known baseline defects declared up front (F1–F12)

---

# Target Architecture

- API gateway with no data store, routing only
- Catalog, Checkout, Inventory and Analytics, each with a private MySQL instance
- Kafka for accepted-scan events
- Docker Compose topology

---

# Data Ownership and Contracts

- Each service can reach only its own database, with no cross grants
- Versioned internal HTTP contracts and one Kafka topic
- Derived catalog projections in Checkout, Inventory and Analytics
- Shared JSON fixtures for provider and consumer tests

---

# Completion Saga

- Freeze → Reserve → Prepare → Commit → Finalize, as local transactions
- Partial purchases: available units are bought, the rest stay in the basket
- Per-attempt receipts under the same transaction ID

---

# Failure Handling and Recovery

- Durable `attemptId`, with every Inventory command idempotent
- Recovery worker with leases, retry and cancel rules
- Bounded deadline: `503 SERVICE_UNAVAILABLE`, while recovery continues in the background
- `409 TRANSACTION_BUSY` for scans during an unresolved completion

---

# Accepted Scans and Analytics

- Transactional outbox in Checkout, relayed to Kafka
- Analytics pipeline: ingest → window → rank-and-publish
- Atomic snapshot publication, so readers never see a partial ranking

---

# Verification

- Module, contract and Testcontainers tests
- System tests against the running stack
- Fault injection with Toxiproxy (interruptions, lost responses, races)
- Reconciliation checks R-1 to R-6

---

# Measured Results: Baseline vs. Redesign

- Scan and start p95 targets met
- Throughput and complete p95 targets missed
- Root cause: row-lock queuing on hot SKUs in Inventory

---

# Performance Iteration

- Shorter Inventory hot-row locking
- Merged completion steps (three Checkout transactions instead of five)
- Binary log off and a Checkout pool of 15
- Attributing each gain: architecture vs. configuration

---

# Lessons and Trade-offs

- Consistency across services costs completion latency
- Environment artifacts can mislead (the gateway-hop stall from Docker port binding)
- A fair comparison needs declared differences and attribution

---

# Q&A
