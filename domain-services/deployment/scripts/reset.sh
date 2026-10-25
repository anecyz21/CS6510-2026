#!/usr/bin/env bash
# Removes every database volume and the Kafka log, then recreates the stack from empty stores
# and waits for readiness. Every measured or validation run starts from this state.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
compose() { docker compose -f "$here/../docker-compose.yml" --env-file "$here/../services.env" "$@"; }
compose down -v --remove-orphans
compose up -d --build
"$here/check-readiness.sh"
