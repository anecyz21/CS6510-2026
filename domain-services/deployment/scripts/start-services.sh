#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/../.."
mvn -q -DskipTests package
java -jar catalog-service/target/catalog-service-*.jar &
java -jar checkout-service/target/checkout-service-*.jar &
java -jar inventory-service/target/inventory-service-*.jar &
java -jar analytics-service/target/analytics-service-*.jar &
java -jar api-gateway/target/api-gateway-*.jar &
wait
