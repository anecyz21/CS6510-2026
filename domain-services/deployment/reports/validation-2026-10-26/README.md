# Full validation run, 2026-10-26 (task T126)

Produced by [`../../scripts/validate-all.sh`](../../scripts/validate-all.sh) from a `reset.sh`
state, 02:12–02:28 UTC, on the code at `8f17db7` plus the uncommitted Phase 9 changes (docs,
contract test, cleanup). Each scenario's raw output is in its own file.
[`SUMMARY.txt`](SUMMARY.txt) lists every step with its exit status, and the `*-summary.txt` files
keep only the test-result lines.

## Results

| Scenario | Evidence | Result |
|---|---|---|
| V0: reset, topology, readiness | `v0-reset.txt`, `v0-compose-ps.txt` | PASS: four databases, Kafka, four services and the gateway healthy |
| V1–V2: module, contract and Testcontainers tests (`mvn verify`) | `v1-mvn-verify*.txt` | PASS: 88 tests, 0 failures (contracts, Catalog, Checkout incl. `OutboxRelayIT`, Inventory ITs, Analytics incl. `ScanIngestIT`, gateway) |
| V1–V9: system tests on the plain stack (all but V7) | `v1-v9-system-tests*.txt` | **FAIL**: 32 tests, 2 failures, both in `FullGatewayContractTest` (see below) |
| V4: contract test re-run after the test fix | `v4-contract-rerun.txt` | **FAIL**: 5 tests, 1 failure, the contract gap below |
| V4: contract test after amendment A7 (OpenAPI 1.2.1) | `v4-contract-after-a7.txt` | **PASS**: 5 tests, 0 failures; reconciliation PASS afterwards |
| V7: interruption, lost response and races (`-Dstack.faults=true`) | `v7-*.txt` | PASS: 7 tests |
| V9: settled reconciliation R-1 to R-6 | `v9-reconciliation.txt` | PASS: 104 committed reservations equal finalized attempts; 4,560 scans ingested; no pending sales |
| V10: `COMPARISON.md` regenerated from the committed runs | `v10-compare.txt` | PASS (regenerated only; the measured runs were not repeated, as requested) |
| V11: every relative link in `README.md` and `ARCHITECTURE.md` resolves | `v11-links.txt` | PASS: 30 links |

## Failures and what was done

1. **`everyOperationAnswers503WhenItsOwnerIsDown`: fixed.** After the Checkout outage, the final
   completion answered `409` instead of `200`.
   - The cause was in the test: it scanned `SKU-000001`, whose stock `PartialFulfillmentTest`
     changes earlier in the suite, so the completion found no stock (`INSUFFICIENT_STOCK`).
   - The service behaved correctly. The test now provisions its own SKU (`SKU-000044`), and the
     re-run passes this case (`v4-contract-rerun.txt`).
2. **`badRequestsOnScanAndPopularItemsAreDocumented`: fixed by amendment A7, approved 2026-10-26.**
   - The services return the error catalog's `400 MISSING_SKU` (scan) and `400 INVALID_LIMIT`
     (popular items).
   - `spec/self-checkout-openapi.yaml` 1.2.0 defines a `400` response only for
     `POST /transactions`, so these two responses do not conform to the canonical contract.
   - This gap predates the redesign: the amendment did not add these responses, and the error
     catalog lists them as existing behavior.
   - The user approved a documentation-only amendment (A7, recorded in
     `specs/004-domain-services/contracts/public-api-amendment.md`). It adds the two `400` responses
     and bumps `info.version` to `1.2.1`; no behavior changed. The contract test then passed 5 of 5
     (`v4-contract-after-a7.txt`).

Every other response of the seven operations conforms to the OpenAPI document. That includes all
`404`, `409` (`TRANSACTION_NOT_OPEN`, `TRANSACTION_BUSY`, `EMPTY_BASKET`, `INSUFFICIENT_STOCK`) and
`503` cases, the latter for every operation with its owner stopped and for completion with
Inventory paused.
