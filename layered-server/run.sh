#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -d out ]; then
  ./build.sh
fi
java -cp out selfcheckout.LayeredServerApplication "$@"
