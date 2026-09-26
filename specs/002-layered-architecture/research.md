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

### Atomic completion

**Decision**: Complete a transaction through one transaction-layer operation that validates the
open transaction and atomically verifies and decrements every required inventory quantity before
marking it complete.

**Rationale**: This prevents partial completion, double completion, and overselling during
concurrent traffic.

**Alternatives considered**: Independent check-then-decrement operations and decrement-on-scan
were rejected because they respectively risk overselling and violate the contract.

### Hopping-window analytics

**Decision**: Record successful scans, retain the configured recent window, and refresh rankings
only at the configured slide interval.

**Rationale**: This matches the shared contract and keeps ranking responsibility in analytics.

**Alternatives considered**: Recomputing on every request and API-layer ranking were rejected
because they conflict with the documented behavior and layer ownership, respectively.
