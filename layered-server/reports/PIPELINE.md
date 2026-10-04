# How the Analytics Pipeline Works

Every time a customer scans an item, the checkout system records the scan in its basket right
away. The scan is then sent to a background analytics pipeline. This means customers do not wait
for popularity calculations before receiving their scan response.

```text
successful scan
    |
    v
ingest  ->  window  ->  rank-and-publish  ->  popular-items result
```

## The three filters

### 1. `ingest` — puts scans in order

`ingest` receives each successful scan and assigns it the next sequence number: 1, 2, 3, and so
on. It then passes the ordered scan to the next filter.

### 2. `window` — keeps the recent history

`window` keeps only the most recent 1,000 scans. When scan 1 arrives, and then after every 500
scans, it takes a safe copy of that recent history and sends it onward.

For example, after 1,500 scans, the window contains scans 501 through 1,500. The first 500 scans
are no longer included in the current popularity calculation.

### 3. `rank-and-publish` — calculates the popular items

`rank-and-publish` counts the items in the copied window, sorts them from most-scanned to
least-scanned, and assigns ranks. If two items have the same count, their SKU breaks the tie.

When all of that work is complete, it publishes one finished result. The
`GET /analytics/popular-items` endpoint reads this latest finished result.

## How the filters are connected

The filters are connected by Java blocking queues:

```text
ingest queue -> window queue -> ranking queue
```

The queues are FIFO: first in, first out. Therefore, scans keep the same order as they move
through the pipeline. Each filter has its own worker thread, so calculating rankings does not
block checkout requests.

## What users see while a new ranking is calculated

The system never returns a half-calculated ranking. While a newer result is being prepared,
requests see the previous complete result. Once the new ranking is ready, it replaces the old one
all at once.

## Safe shutdown

When the server stops normally, it first stops accepting new analytics scans. It then lets scans
already in the queues travel through every filter before the workers stop. This prevents accepted
scans from being silently lost during shutdown.
