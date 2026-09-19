# Self-Checkout Monolith

Spring Boot / MySQL implementation of the shared self-checkout API. It uses a single deployable application, MySQL 8 with Flyway migrations, and synchronous HTTP on port 8080.

## Prerequisites

- JDK 21
- MySQL 8, with a database user able to create/use `self_checkout`

## Build and run

```sh
./build.sh
./run.sh
```

On Windows, use `mvnw.cmd -q package` and `java -jar target/self-checkout-monolith-0.1.0-SNAPSHOT.jar`.

## Configuration

`DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` configure MySQL. `CATALOG_SIZE`, `STOCK_PER_ITEM`, `LOW_STOCK_THRESHOLD`, `WINDOW_SIZE`, and `SLIDE_INTERVAL` override the corresponding application defaults. The default HTTP port is `8080`.

The baseline uses Spring MVC with platform threads; framework and database overhead are intentionally part of recorded performance measurements. Keep the same runtime configuration for comparable later-week measurements.

## Verification

```sh
./mvnw test
```

Run the normal and stress load-client workloads only against a freshly seeded MySQL database, then save their JSON reports under `reports/` with reconciliation output.
