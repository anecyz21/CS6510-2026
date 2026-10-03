# Layered Self-Checkout Server

This is the layered implementation of the shared self-checkout OpenAPI contract.

## Build and run

```bash
./build.sh
./run.sh 8080 2000 10000 50
```

The arguments are port, catalog size, initial stock per item, and low-stock threshold.
See `../specs/002-layered-architecture/quickstart.md` for smoke checks and workload commands.

## Validation status

- Contract smoke test: passed for catalog, transaction start, scan, completion, status,
  popular-items, and low-stock endpoints on 2026-10-03.
- Partial checkout: passed. A mixed-stock basket returned a receipt for the available SKU and an
  `unavailableItems` entry for the retained SKU; its transaction remained `OPEN`. A two-unit scan
  of a one-unit SKU fulfilled one unit and retained one unit. A normal receipt omits the optional
  unavailable-items field.
- Inventory invariant: passed with 1,000 concurrent atomic fulfillment attempts and final stock 0.
- Layer-boundary review: passed; see [ARCHITECTURE.md](ARCHITECTURE.md).
- Normal and stress workload reports: generated on 2026-10-03 and stored in `reports/`.
