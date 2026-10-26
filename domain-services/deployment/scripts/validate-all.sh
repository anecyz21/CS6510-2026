#!/usr/bin/env bash
# Full quickstart validation V0–V11 (task T126). Starts from reset.sh and writes each scenario's
# output to domain-services/deployment/reports/validation-<UTC date>/. It never stops at a failure:
# every step runs, and SUMMARY.txt lists each step's exit status.
#
# Usage: validate-all.sh [--out DIR]
# Requires JDK 21 (JAVA_HOME), Maven, Docker Compose v2, bash and jq. Takes about 40 minutes.
# V10 does not re-run the measured workloads; it regenerates COMPARISON.md from the committed runs.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
deploy="$(cd "$here/.." && pwd)"
ds="$(cd "$deploy/.." && pwd)"
out="$deploy/reports/validation-$(date -u +%Y-%m-%d)"
[ "${1:-}" = "--out" ] && out="$2"
mkdir -p "$out"
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
compose() { docker compose -f "$deploy/docker-compose.yml" --env-file "$deploy/services.env" "$@"; }
faults() { docker compose -f "$deploy/docker-compose.yml" -f "$deploy/docker-compose.faults.yml" --env-file "$deploy/services.env" "$@"; }
fault_tests='CancelCommitRaceTest,DuplicateCompletionJoinTest,InterruptionRecoveryTest,LostResponseTest'

: > "$out/SUMMARY.txt"
step() { # name file command...
  local name="$1" file="$2"; shift 2
  local started; started=$(date -u +%H:%M:%S)
  { echo "# $name"; echo "# command: $*"; echo "# started: $(date -u +%Y-%m-%dT%H:%M:%SZ)"; echo; } > "$out/$file"
  "$@" >> "$out/$file" 2>&1
  local status=$?
  printf '%-58s %s  exit %s  (%s, %s)\n' "$name" "$([ $status -eq 0 ] && echo PASS || echo FAIL)" "$status" "$file" "$started" >> "$out/SUMMARY.txt"
  return 0
}
surefire_summary() { # file: keep only the per-class and total test lines for readability
  grep -aE "Tests run:|FAIL|AssertionFailed|does not conform|BUILD" "$1" | grep -av "^\[INFO\] Downloading" > "${1%.txt}-summary.txt" || true
}

step "V0 reset to empty stores and readiness" v0-reset.txt "$here/reset.sh"
step "V0 Compose topology (services and health)" v0-compose-ps.txt compose ps
step "V1-V2 module, contract and Testcontainers tests (mvn verify)" v1-mvn-verify.txt \
  mvn -f "$ds/pom.xml" verify
surefire_summary "$out/v1-mvn-verify.txt"
step "V1-V9 system tests on the plain stack (all but V7 faults)" v1-v9-system-tests.txt \
  mvn -f "$ds/pom.xml" -pl integration-tests -am -Psystem-tests test "-Dtest=!${fault_tests//,/,!}" -Dsurefire.failIfNoSpecifiedTests=false
surefire_summary "$out/v1-v9-system-tests.txt"
step "V7 start the fault-injection override" v7-faults-up.txt faults up -d
step "V7 readiness with faults override" v7-readiness.txt "$here/check-readiness.sh"
step "V7 interruption, lost response and race tests (-Dstack.faults=true)" v7-fault-tests.txt \
  mvn -f "$ds/pom.xml" -pl integration-tests -am -Psystem-tests test -Dstack.faults=true "-Dtest=$fault_tests" -Dsurefire.failIfNoSpecifiedTests=false
surefire_summary "$out/v7-fault-tests.txt"
step "V7 restore the plain topology" v7-faults-down.txt compose up -d --remove-orphans
step "V7 readiness after restore" v7-restore-readiness.txt "$here/check-readiness.sh"
step "V9 settled reconciliation R-1..R-6" v9-reconciliation.txt "$here/reconcile.sh" --wait-settled 120
step "V10 regenerate COMPARISON.md from the committed runs" v10-compare.txt "$here/compare-reports.sh"
step "V11 every relative link in the entry-point docs resolves" v11-links.txt bash -c '
  status=0
  for doc in "$1/README.md" "$1/ARCHITECTURE.md"; do
    dir=$(dirname "$doc")
    for link in $(grep -oE "\]\([^)#]+" "$doc" | sed "s/^](//" | grep -vE "^(https?:|mailto:)"); do
      if [ -e "$dir/$link" ]; then echo "ok      $(basename "$doc") -> $link"; else echo "MISSING $(basename "$doc") -> $link"; status=1; fi
    done
  done
  exit $status' _ "$deploy"

echo "validation finished $(date -u +%Y-%m-%dT%H:%M:%SZ); outputs in $out" >> "$out/SUMMARY.txt"
cat "$out/SUMMARY.txt"
