#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac -d out $(find src -name '*.java' | sort)
echo "Built layered server. Run with: ./run.sh [port] [catalogSize] [stockPerItem] [lowStockThreshold]"
