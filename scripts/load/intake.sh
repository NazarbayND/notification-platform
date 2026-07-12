#!/usr/bin/env bash
set -euo pipefail

mkdir -p build/load-results
SUMMARY_OUTPUT="${SUMMARY_OUTPUT:-build/load-results/intake-summary.json}"
mkdir -p "$(dirname "${SUMMARY_OUTPUT}")"
BASE_URL="${BASE_URL:-http://localhost:8081}" \
RATE="${RATE:-100}" \
DURATION="${DURATION:-30s}" \
k6 run --summary-trend-stats="avg,min,med,p(90),p(95),p(99),max" \
  --summary-export="${SUMMARY_OUTPUT}" tests/load/intake-test.js
