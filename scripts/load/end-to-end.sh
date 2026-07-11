#!/usr/bin/env bash
set -euo pipefail

mkdir -p build/load-results
BASE_URL="${BASE_URL:-http://localhost:8081}" \
PROJECTION_URL="${PROJECTION_URL:-http://localhost:8092}" \
RATE="${RATE:-50}" \
DURATION="${DURATION:-30s}" \
k6 run --summary-trend-stats="avg,min,med,p(90),p(95),p(99),max" \
  --summary-export=build/load-results/end-to-end-summary.json tests/load/end-to-end-test.js
