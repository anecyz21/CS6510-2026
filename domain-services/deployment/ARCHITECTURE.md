# Database-coordinated services

Catalog owns `catalog`, Checkout owns `checkout`, Inventory owns `inventory`, and Analytics owns `analytics`. Each is an executable Spring Boot JAR with its own Flyway migrations and least-privilege account.

Only the gateway uses HTTP to call services. Checkout reads `catalog.active_items`, invokes `inventory.fulfill_checkout`, and writes its accepted-scan outbox atomically with its basket change. Analytics reads that outbox, checkpoints scan IDs, and runs `ingest → window → rank-and-publish`; a restart replays unprocessed rows without double-counting.

The gateway has no datasource. Grant definitions and their database-interface rationale are in `mysql/init/001-service-boundaries.sql`.
