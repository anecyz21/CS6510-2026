# Deployment and verification

Prerequisites: JDK 21, Docker Compose, and ports 3307 plus 8080–8084. From the repository root, build with `mvn verify`, then run `docker compose -f domain-services/deployment/docker-compose.yml up --build`. Once owner Flyway migrations finish, run `domain-services/deployment/scripts/grant-cross-domain-interfaces.sh`; this applies only the three documented object grants after those objects exist. Run `domain-services/deployment/scripts/check-readiness.sh` after the health checks succeed.

For independent JAR use, initialize MySQL with `domain-services/deployment/mysql/init/001-service-boundaries.sql`, export the service-specific datasource values from `domain-services/deployment/services.env.example`, then run `domain-services/deployment/scripts/start-services.sh`. Each domain service becomes ready with MySQL alone; Analytics may stop and restart without blocking Checkout.

Validation commands: `mvn test` using JDK 21 passed for the full reactor on 2026-10-16 (the default run skips five opt-in MySQL integration cases). `docker compose -f domain-services/deployment/docker-compose.yml config --quiet` passed. The Compose quickstart was completed with Docker Desktop: all six containers reported healthy, and the gateway health endpoint returned `{"status":"UP"}`. The cross-domain object grants were applied after Flyway migrations.

The unchanged client completed its 10-station/60-second and 200-station/180-second workloads with zero errors after adding bounded checkout deadlock retry. Exact timestamped JSON and latency/throughput evidence are in [historical reports](reports/historical/README.md) (inadequate stock; not baseline evidence); the inventory reconciliation query returned no rows after the stress run.

Boundary evidence: [architecture and data flow](ARCHITECTURE.md), [least-privilege grants](mysql/init/001-service-boundaries.sql), [no-direct-call guard](../integration-tests/src/test/java/edu/northeastern/cs6510/boundaries/NoDirectDomainCallsTest.java), and [inventory reconciliation](../integration-tests/scripts/reconcile-inventory.sql). Workload reports and reconciliation output belong in `reports/`.
