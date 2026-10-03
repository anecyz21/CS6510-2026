# Research: Layered Server Architecture

## Decisions

### Java 21 with JDK-only runtime

**Decision**: Implement the weekly server with Java 21 and the JDK's built-in HTTP server.

**Rationale**: The load client requires JDK 21+, and the reference server is a no-dependency Java
21 program. This keeps the server independently runnable and avoids a framework change affecting
architecture comparisons.

**Alternatives considered**: A third-party web framework was rejected because it adds a runtime
dependency without a stated requirement. Reusing the reference server was rejected because the
constitution says it is not an architectural model and it lacks the required boundaries.

### In-memory data access

**Decision**: Keep catalog, transactions, inventory, and analytics history in a dedicated in-memory
data-access layer.

**Rationale**: The assignment requires clear persistence boundaries, not durable external storage.

**Alternatives considered**: An external database was rejected because no durability or deployment
requirement exists. State in request handlers was rejected because it violates the API/data-access
boundary.

### Atomic partial completion

**Decision**: Complete a transaction through one transaction-layer operation that, under the
data-access inventory lock, determines the fulfillable quantity for each scanned SKU, decrements
only those quantities, and removes only fulfilled units from the open basket. A transaction becomes
completed only when no scanned units remain; otherwise it stays open for a later attempt.

**Rationale**: This meets the constitution's partial-completion rule while preserving non-negative
inventory and preventing duplicate sales during concurrent traffic. It also handles a basket with
multiple units of one SKU: available units are fulfilled and only the shortage remains.

**Alternatives considered**: All-or-nothing completion was rejected because it conflicts with the
constitution. Independent check-then-decrement operations and decrement-on-scan were rejected
because they respectively risk overselling and violate the contract.

### Receipt-compatible unavailable-item notice

**Decision**: Return the documented receipt fields for fulfilled lines and include an optional
`unavailableItems` detail containing each retained SKU and quantity. The transaction status remains
open when any units remain in its basket.

**Rationale**: The original OpenAPI receipt schema has no `additionalProperties: false` constraint,
so the optional detail is schema-compatible while preserving every documented field. It gives the
customer an explicit account of unavailable items as required by the constitution.

**Alternatives considered**: Returning a 409 for stock shortages was rejected because it would
block available lines. Encoding shortages only in a message or asking the customer to infer them
from omitted receipt lines was rejected because it does not explicitly identify retained items.

### Hopping-window analytics

**Decision**: Record successful scans, retain the configured recent window, and refresh rankings
only at the configured slide interval.

**Rationale**: This matches the shared contract and keeps ranking responsibility in analytics.

**Alternatives considered**: Recomputing on every request and API-layer ranking were rejected
because they conflict with the documented behavior and layer ownership, respectively.
