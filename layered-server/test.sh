#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out-test
mkdir -p out-test
javac --release 21 -d out-test $(find src test -name '*.java' | sort)
for test_class in "$@"; do
  java -cp out-test "$test_class"
done
