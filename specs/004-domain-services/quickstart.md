# Quickstart: Validate Database-Coordinated Domain Services

## Prerequisites

- JDK 21 and Docker Compose are available.
- The supplied OpenAPI contract and load client remain unmodified.
- Ports for the gateway and five local processes are available.

## Start the system

From the repository root, build the Maven reactor and start the centralized database plus the
four domain services and gateway using the documented deployment configuration.

```bash
./mvnw verify
docker compose -f domain-services/deployment/docker-compose.yml up --build
```

Wait for Catalog, Checkout, Inventory, Analytics, and the gateway readiness checks to succeed.
Stop and start Analytics independently; it must become ready without restarting Checkout.

## Contract and boundary checks

Run the contract suite through the gateway. Verify the seven public routes against
[the public and database boundary contract](contracts/public-and-database-boundaries.md).

Run the boundary integration suite. It must prove that:

1. Every service can start with only the centralized database available.
2. Each service can write only its owned schema and is denied prohibited writes.
3. Checkout records a basket scan and an analytics outbox record atomically.
4. Analytics can restart, process pending scans idempotently, and publish complete snapshots.
5. Checkout completion preserves partial completion and per-SKU inventory reconciliation.
6. No domain service makes a direct call to another domain service.

## Normal and stress workloads

Start each workload from a freshly provisioned database with enough stock to avoid deliberate
stock exhaustion. Build and run the unmodified client against the gateway:

```bash
cd load-client
./build.sh
./run.sh --baseUrl=http://localhost:8080 --stations=10 --duration=60 --reportDir=../domain-services/deployment/reports
./run.sh --baseUrl=http://localhost:8080 --stations=200 --duration=180 --reportDir=../domain-services/deployment/reports
```

For each generated report, record the timestamped filename, service version, deployment
topology, centralized-database provisioning, operation error counts, mean/p95/p99 latency, and
throughput. Run the documented SQL reconciliation before resetting the database.

## Expected outcomes

- All public contract tests pass through the gateway.
- Normal workload has no client operation errors.
- Stress workload preserves inventory reconciliation; any errors are retained and explained.
- An Analytics restart neither rejects a valid checkout scan nor duplicates the scan's analytics
  contribution.
- Architecture evidence shows only the gateway performs service transport calls; domain services
  coordinate through documented database interfaces.
