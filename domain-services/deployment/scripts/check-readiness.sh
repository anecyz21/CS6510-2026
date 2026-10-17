#!/usr/bin/env sh
set -eu
for port in 8081 8082 8083 8084 8080; do
  curl --fail --retry 30 --retry-delay 1 "http://localhost:${port}/actuator/health" >/dev/null
done
echo "all services ready"
