#!/usr/bin/env bash
set -euo pipefail

mkdir -p build/load-results
SUMMARY_OUTPUT="${SUMMARY_OUTPUT:-build/load-results/burst-summary.json}"
mkdir -p "$(dirname "${SUMMARY_OUTPUT}")"
BASE_URL="${BASE_URL:-http://localhost:8081}" \
BURST_RATE="${BURST_RATE:-1000}" \
k6 run --summary-trend-stats="avg,min,med,p(90),p(95),p(99),max" \
  --summary-export="${SUMMARY_OUTPUT}" tests/load/burst-test.js
