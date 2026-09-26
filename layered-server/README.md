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
  popular-items, and low-stock endpoints.
- Layer-boundary review: passed; see [ARCHITECTURE.md](ARCHITECTURE.md).
- Normal and stress workload reports: stored in `reports/` when generated for submission.
