# Quickstart: Validate the Windowed Analytics Pipeline

## Prerequisites

- JDK 21 or newer is available.
- The supplied contract and load client remain unmodified.
- Run commands from a shell that supports the repository scripts.

## Build and start

From the repository root:

```bash
cd layered-server
./build.sh
./run.sh 8080 2000 100000000 50
```

Keep the server running. The high initial stock prevents intentional stock exhaustion from
dominating the workload runs.

## Contract and snapshot smoke checks

In another terminal:

```bash
curl http://localhost:8080/items
curl "http://localhost:8080/analytics/popular-items?limit=10"
```

Create a transaction, scan a catalog SKU, and request popular items again. The scan response must
remain synchronous and contract-compatible. The analytics response must contain only complete
snapshot metadata and rankings. Compare it with
[the authoritative API contract](../../spec/self-checkout-openapi.yaml).

## Automated feature checks

Run the unit and integration checks introduced with implementation. They must prove that:

1. At 1,500 accepted scans, the ranking and bounds describe the most recent 1,000 scans.
2. A complete snapshot is published at scan 1 and every 500th scan thereafter.
3. Concurrent scans and analytics reads never expose a partial ranking.
4. Orderly close drains queued accepted scans without loss or deadlock.

Run the existing inventory-invariant check too, confirming the analytics change did not affect
checkout completion.

## Required JSON reports

Build the supplied client without modifying it:

```bash
cd load-client
./build.sh
```

Generate the default report by omitting station and duration flags:

```bash
./run.sh --baseUrl=http://localhost:8080 --reportDir=../layered-server/reports
```

Generate the required stress report:

```bash
./run.sh --baseUrl=http://localhost:8080 --stations=100 --duration=120 --reportDir=../layered-server/reports
```

Record both timestamped filenames in `layered-server/reports/README.md`. Confirm that each JSON
contains operation outcomes, latency percentiles, throughput, low-stock observations, and
popular-item results. Existing 200-station/180-second reports cannot substitute for the required
stress run.

## Pipeline review

Review [the filter and HTTP mapping](contracts/pipeline-and-http.md) and the implementation's
`layered-server/ARCHITECTURE.md`. A reviewer must be able to identify the three filters, each
filter's purpose, and each FIFO connection.
