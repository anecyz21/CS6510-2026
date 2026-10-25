#!/usr/bin/env bash
# Builds every deployable, starts the private-store topology and waits for readiness.
# Requires domain-services/deployment/services.env (copy services.env.example) and JDK 21.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
[ -f "$here/../services.env" ] || { echo "create $here/../services.env from services.env.example first" >&2; exit 1; }
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -f "$here/../../pom.xml" -DskipTests package
docker compose -f "$here/../docker-compose.yml" --env-file "$here/../services.env" up -d --build
"$here/check-readiness.sh"
