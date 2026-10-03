# Quickstart: Validate the Layered Server

## Prerequisites

- JDK 21 or later is available on the command line.
- The supplied repository contract and load client are present and unmodified.

## Build and start

From the repository root, build and start the planned weekly server:

```bash
cd layered-server
./build.sh
./run.sh 8080 2000 10000 50
```

The server should listen on port 8080. Startup values represent port, catalog size, initial stock
per item, and default low-stock threshold.

## Contract smoke checks

In another terminal, verify representative contract behavior:

```bash
curl http://localhost:8080/items
curl -X POST http://localhost:8080/transactions -H "Content-Type: application/json" -d '{"stationId":"station-01"}'
curl "http://localhost:8080/analytics/popular-items?limit=10"
curl "http://localhost:8080/inventory/low-stock"
```

Use the returned transaction identifier to scan an existing SKU and then complete it. Compare all
response fields and status codes with [the authoritative contract](../../spec/self-checkout-openapi.yaml).

## Partial-completion check

Start a small local catalog with one unit of stock per SKU. First complete a transaction containing
one unit of `SKU-000001` so that SKU is exhausted. Then start a second transaction, scan
`SKU-000001` and `SKU-000002`, and complete it. Expected outcome: the receipt contains
`SKU-000002`; `unavailableItems` identifies `SKU-000001`; and a transaction-status check shows the
second transaction remains open with the unavailable unit retained. A later completion must succeed
if stock becomes available, without charging the already fulfilled unit again.

## Normal workload

Run the supplied load client without changing it:

```bash
cd load-client
./build.sh
./run.sh --baseUrl=http://localhost:8080 --stations=10 --duration=60 --reportDir=../layered-server/reports
```

Expected outcome: no errors for start, scan, or completion; a timestamped JSON report records mean,
p95, and p99 latency.

## Stress workload and invariant

```bash
cd load-client
./run.sh --baseUrl=http://localhost:8080 --stations=200 --duration=180 --reportDir=../layered-server/reports
```

Retain the report even if errors occur and explain them. Verify each SKU's final stock equals its
initial stock minus completed-transaction line items and never falls below zero. Compare popular
items with the documented hopping-window metadata.

## Boundary review

Review the source layout against [the layer map](contracts/http-api.md): API handlers delegate to
transactions or analytics, completion delegates storage mutation to data access, and analytics owns
ranking calculations.
