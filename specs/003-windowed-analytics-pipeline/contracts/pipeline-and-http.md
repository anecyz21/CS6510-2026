# Pipeline and HTTP Contract Preservation

The authoritative external contract is
[spec/self-checkout-openapi.yaml](../../../spec/self-checkout-openapi.yaml). This design does not
add or alter public routes, fields, status codes, or synchronous request behavior.

```text
Successful transaction scan
  -> ingest queue -> ingest filter
  -> window queue -> window filter
  -> ranking queue -> rank-and-publish filter
  -> atomic latest snapshot -> GET /analytics/popular-items
```

| Filter | Input | Output | Single responsibility |
| --- | --- | --- | --- |
| `ingest` | Accepted scan submission | Ordered `ScanEvent` | Assign the next sequence and forward it. |
| `window` | `ScanEvent` | `WindowUpdate` | Retain the recent window and create immutable updates. |
| `rank-and-publish` | `WindowUpdate` | Latest `AnalyticsSnapshot` | Count, rank, resolve names, and publish one complete snapshot. |

All pipes are FIFO. Filters communicate only with their adjacent pipe. A terminal message follows
ordinary work during orderly shutdown.

| Endpoint | Preserved behavior | Pipeline impact |
| --- | --- | --- |
| `POST /transactions/{id}/items` | Existing synchronous result or error response. | Submit only successful scans to `ingest`; ranking never runs in the request. |
| `GET /analytics/popular-items` | Existing window metadata and limited ranking response. | Read one complete immutable snapshot, prior or next but never partial. |
| All other endpoints | Existing documented response behavior. | No pipeline interaction. |
